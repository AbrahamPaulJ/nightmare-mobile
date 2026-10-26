"""Wan 2.2 Animate 14B preprocessing (animation mode, no retargeting), DWPose edition.

Turns a driving video + a reference picture into what Wan's animate.py consumes:
  DIR/pose/0000.png ...   skeleton frames, W x H (src_pose.mp4 upstream)
  DIR/face/0000.png ...   face crops, 512 x 512   (src_face.mp4 upstream)
  DIR/ref.png             reference picture letterboxed to W x H (what animate.py builds from src_ref.png)
  DIR/keypoints.json      raw COCO-WholeBody 133 x (x, y, score) per frame, source-pixel coords (for porting checks)

Upstream: Wan2.2 wan/modules/animate/preprocess/ (process_pipepline.py, pose2d.py, pose2d_utils.py,
human_visualization.py, utils.py) and wan/animate.py. The 2D pose models are swapped for DWPose
(yolox_l.onnx + dw-ll_ucoco_384.onnx, yzd-v/DWPose); the frame sampling, body/hand split, drawing and
face crop are copied from upstream (cited per function). See PREPROCESS_SPEC.md.

Usage:
  python -I prep.py --video V.mp4 --ref R.jpg --out DIR --width 832 --height 480 --fps 16 --frames 33 --start 0
"""
import argparse
import json
import math
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))


# ----------------------------------------------------------------------------------------------
# Frame sampling. From utils.py get_frame_indices (start_frame is 0 upstream; here it is --start).
# np.round is round-half-to-even, keep that in a port.
# ----------------------------------------------------------------------------------------------
def get_frame_indices(frame_num, video_fps, clip_length, train_fps, start_frame=0):
    times = np.arange(0, clip_length) / train_fps
    frame_indices = start_frame + np.round(times * video_fps).astype(int)
    frame_indices = np.clip(frame_indices, 0, frame_num - 1)
    return frame_indices.tolist()


def read_frames(path, start, fps, frames):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        sys.exit(f"cannot open video {path}")
    video_fps = cap.get(cv2.CAP_PROP_FPS)
    frame_num = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if fps == -1:
        fps = video_fps
    upstream_target = int((frame_num - start) / video_fps * fps)  # process_pipepline.py L158 (with start)
    if frames > upstream_target:
        print(f"warning: {frames} frames asked, the video has {upstream_target} at {fps} fps from frame {start}; "
              f"the tail repeats the last frame")
    idxs = get_frame_indices(frame_num, video_fps, frames, fps, start)
    want = set(idxs)
    got = {}
    i = 0
    last = None
    while i <= max(idxs):
        ok, bgr = cap.read()
        if not ok:
            break
        last = i
        if i in want:
            got[i] = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        i += 1
    cap.release()
    if last is None:
        sys.exit("video has no decodable frames")
    out = []
    for k in idxs:
        if k not in got:  # container frame count over-reported: clamp to the last decoded frame
            k = max(j for j in got) if got else None
            if k is None:
                sys.exit("no requested frame decoded")
        out.append(got[k])
    print(f"video {frame_num} frames @ {video_fps:.3f} fps, sampled at {fps} fps, indices {idxs}")
    return out, video_fps, idxs


# ----------------------------------------------------------------------------------------------
# DWPose detector: YOLOX-L (onnxdet.py in DWPose / controlnet_aux), person box selection copied
# from upstream pose2d.py Yolo.process_results (select_type='max', strict=True, ratio 0.4).
# ----------------------------------------------------------------------------------------------
class YoloX:
    def __init__(self, path):
        self.sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        self.inp = self.sess.get_inputs()[0].name
        self.size = (640, 640)
        strides = [8, 16, 32]
        grids, exp = [], []
        for s in strides:
            hs, ws = self.size[0] // s, self.size[1] // s
            xv, yv = np.meshgrid(np.arange(ws), np.arange(hs))
            g = np.stack((xv, yv), 2).reshape(-1, 2)
            grids.append(g)
            exp.append(np.full((g.shape[0], 1), s))
        self.grids = np.concatenate(grids, 0).astype(np.float32)
        self.strides = np.concatenate(exp, 0).astype(np.float32)

    def __call__(self, rgb):
        h, w = rgb.shape[:2]
        r = min(self.size[0] / h, self.size[1] / w)
        nh, nw = int(h * r), int(w * r)
        pad = np.full((self.size[0], self.size[1], 3), 114, np.uint8)
        pad[:nh, :nw] = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR).astype(np.uint8)
        x = np.ascontiguousarray(pad.transpose(2, 0, 1), dtype=np.float32)[None]  # 0..255, no normalisation
        out = self.sess.run(None, {self.inp: x})[0][0]  # 8400 x 85: cx, cy, w, h, obj, 80 classes
        xy = (out[:, :2] + self.grids) * self.strides
        wh = np.exp(out[:, 2:4]) * self.strides
        score = out[:, 4] * out[:, 5]  # person = class 0
        boxes = np.concatenate([xy - wh / 2, xy + wh / 2], 1) / r
        keep = score > 0.1  # DWPose multiclass_nms score_thr
        boxes, score = boxes[keep], score[keep]
        if len(boxes):
            xywh = [[float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])] for b in boxes]
            idx = cv2.dnn.NMSBoxes(xywh, score.tolist(), 0.1, 0.45)  # DWPose nms_thr 0.45
            idx = np.array(idx).reshape(-1)
            boxes, score = boxes[idx], score[idx]
            keep = score > 0.3  # DWPose final person threshold
            boxes, score = boxes[keep], score[keep]
        return self.select(boxes, score, (h, w))

    @staticmethod
    def select(boxes, scores, shape_raw, ratio=0.4):
        # pose2d.py Yolo.process_results, select_type='max', strict=True; full frame if nothing found
        if len(boxes) == 0:
            return np.array([0.0, 0.0, shape_raw[1], shape_raw[0], -1.0])
        max_shape = max(max(b[2] - b[0], b[3] - b[1]) for b in boxes)
        best, best_size = -1, shape_raw[0] * shape_raw[1] * -10
        for i, b in enumerate(boxes):
            size = (b[2] - b[0]) * (b[3] - b[1])
            shp = max(b[2] - b[0], b[3] - b[1])
            if size > best_size:
                if shp < max_shape * ratio:
                    continue
                best_size, max_shape, best = size, shp, i
        b = boxes[best]
        return np.array([b[0], b[1], b[2], b[3], scores[best]])


# ----------------------------------------------------------------------------------------------
# DWPose pose: RTMPose-L wholebody 384x288 SimCC (onnxpose.py in DWPose / controlnet_aux).
# Output is COCO-WholeBody 133 in the same order as upstream ViTPose-H wholebody.
# ----------------------------------------------------------------------------------------------
class DWPose:
    MEAN = np.array([123.675, 116.28, 103.53], np.float32)
    STD = np.array([58.395, 57.12, 57.375], np.float32)

    def __init__(self, path):
        self.sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        self.inp = self.sess.get_inputs()[0].name
        self.in_w, self.in_h = 288, 384

    def __call__(self, rgb, bbox):
        x0, y0, x1, y1 = bbox[:4]
        if bbox[-1] <= 0 or (x1 - x0) < 10 or (y1 - y0) < 10:  # pose2d.py ViTPose.preprocess fallback
            x0, y0, x1, y1 = 0, 0, rgb.shape[1], rgb.shape[0]
        center = np.array([(x0 + x1) / 2, (y0 + y1) / 2], np.float64)
        scale = np.array([x1 - x0, y1 - y0], np.float64) * 1.25  # bbox_xyxy2cs padding=1.25
        aspect = self.in_w / self.in_h  # _fix_aspect_ratio
        if scale[0] > scale[1] * aspect:
            scale[1] = scale[0] / aspect
        else:
            scale[0] = scale[1] * aspect
        s = self.in_w / scale[0]  # rot=0 warp: uniform scale, bbox centre -> crop centre
        M = np.array([[s, 0, self.in_w / 2 - s * center[0]], [0, s, self.in_h / 2 - s * center[1]]], np.float64)
        crop = cv2.warpAffine(rgb, M, (self.in_w, self.in_h), flags=cv2.INTER_LINEAR)
        x = ((crop.astype(np.float32) - self.MEAN) / self.STD).transpose(2, 0, 1)[None]
        sx, sy = self.sess.run(None, {self.inp: np.ascontiguousarray(x)})
        sx, sy = sx[0], sy[0]  # 133 x 576, 133 x 768 (split ratio 2)
        lx, ly = sx.argmax(1).astype(np.float32), sy.argmax(1).astype(np.float32)
        vals = np.minimum(sx.max(1), sy.max(1))  # get_simcc_maximum
        kp = np.stack([lx, ly], 1) / 2.0
        kp = kp / np.array([self.in_w, self.in_h]) * scale + center - scale / 2
        kp[vals <= 0] = -1
        return np.concatenate([kp, vals[:, None]], 1)  # 133 x 3, source pixels, raw SimCC score


# ----------------------------------------------------------------------------------------------
# From pose2d_utils.py split_kp2ds_for_aa / load_pose_metas_from_kp2ds_seq / AAPoseMeta.from_humanapi_meta
# ----------------------------------------------------------------------------------------------
BODY_A = [0, 6, 6, 8, 10, 5, 7, 9, 12, 14, 16, 11, 13, 15, 2, 1, 4, 3, 17, 20]
BODY_B = [0, 5, 6, 8, 10, 5, 7, 9, 12, 14, 16, 11, 13, 15, 2, 1, 4, 3, 18, 21]


def split_kp2ds_for_aa(kp2ds):
    body = (kp2ds[BODY_A] + kp2ds[BODY_B]) / 2  # score column is averaged too
    return body.copy(), kp2ds[91:112].copy(), kp2ds[112:133].copy(), kp2ds[22:91].copy()


def load_pose_metas_from_kp2ds_seq(kp2ds_seq, width, height):
    metas, last_body = [], None
    for kps in kp2ds_seq:
        kps = kps.copy()
        kps[:, 0] /= width
        kps[:, 1] /= height
        body, lhand, rhand, face = split_kp2ds_for_aa(kps)
        if body[:, :2].min(axis=1).max() < 0 and last_body is not None:  # upstream: NameError on frame 0
            body = last_body
        last_body = body
        metas.append({"width": width, "height": height, "keypoints_body": body,
                      "keypoints_left_hand": lhand, "keypoints_right_hand": rhand, "keypoints_face": face})
    return metas


def to_pixels(meta):
    wh = (meta["width"], meta["height"])
    return (np.concatenate([meta["keypoints_body"][:, :2] * wh, meta["keypoints_body"][:, 2:3]], 1),
            np.concatenate([meta["keypoints_left_hand"][:, :2] * wh, meta["keypoints_left_hand"][:, 2:3]], 1),
            np.concatenate([meta["keypoints_right_hand"][:, :2] * wh, meta["keypoints_right_hand"][:, 2:3]], 1))


# ----------------------------------------------------------------------------------------------
# Drawing. From human_visualization.py draw_aapose_by_meta_new -> draw_aapose_new (threshold=0.5,
# stickwidth_type='v2', draw_hand=True, draw_head=True) and draw_handpose_new. Canvas is RGB.
# ----------------------------------------------------------------------------------------------
LIMB_SEQ = [[2, 3], [2, 6], [3, 4], [4, 5], [6, 7], [7, 8], [2, 9], [9, 10], [10, 11], [2, 12], [12, 13],
            [13, 14], [2, 1], [1, 15], [15, 17], [1, 16], [16, 18], [14, 19], [11, 20]]
COLORS = [[255, 0, 0], [255, 85, 0], [255, 170, 0], [255, 255, 0], [170, 255, 0], [85, 255, 0], [0, 255, 0],
          [0, 255, 85], [0, 255, 170], [0, 255, 255], [0, 170, 255], [0, 85, 255], [0, 0, 255], [85, 0, 255],
          [170, 0, 255], [255, 0, 255], [255, 0, 170], [255, 0, 85], [200, 200, 0], [100, 100, 0]]
HAND_EDGES = [[0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8], [0, 9], [9, 10], [10, 11],
              [11, 12], [0, 13], [13, 14], [14, 15], [15, 16], [0, 17], [17, 18], [18, 19], [19, 20]]


def hsv_to_rgb_full(h):
    # matplotlib.colors.hsv_to_rgb([h, 1, 1]) * 255 (s = v = 1)
    i = int(math.floor(h * 6.0)) % 6
    f = h * 6.0 - math.floor(h * 6.0)
    q, t = 1.0 - f, f
    rgb = [(1, t, 0), (q, 1, 0), (0, 1, t), (0, q, 1), (t, 0, 1), (1, 0, q)][i]
    return tuple(float(c) * 255.0 for c in rgb)


def draw_handpose_new(canvas, keypoints, hand_score_th=0.5):
    eps = 0.01
    H, W, _ = canvas.shape
    stickwidth = max(max(int(min(H, W) / 200) - 1, 1) // 2, 1)  # 'v2'
    for ie, (e1, e2) in enumerate(HAND_EDGES):
        k1, k2 = keypoints[e1], keypoints[e2]
        if k1[2] < hand_score_th or k2[2] < hand_score_th:
            continue
        x1, y1, x2, y2 = int(k1[0]), int(k1[1]), int(k2[0]), int(k2[1])
        if x1 > eps and y1 > eps and x2 > eps and y2 > eps:
            cv2.line(canvas, (x1, y1), (x2, y2), hsv_to_rgb_full(ie / float(len(HAND_EDGES))), thickness=stickwidth)
    for k in keypoints:
        if k[2] < hand_score_th:
            continue
        x, y = int(k[0]), int(k[1])
        if x > eps and y > eps:
            cv2.circle(canvas, (x, y), stickwidth, (0, 0, 255), thickness=-1)
    return canvas


def draw_aapose_new(img, body, lhand, rhand, threshold=0.5):
    H, W, _ = img.shape
    stickwidth = max(int(min(H, W) / 200) - 1, 1)  # 'v2'
    for (a, b), color in zip(LIMB_SEQ, COLORS):
        k1, k2 = body[a - 1], body[b - 1]
        if k1[-1] < threshold or k2[-1] < threshold:
            continue
        Y = np.array([k1[0], k2[0]])
        X = np.array([k1[1], k2[1]])
        mX, mY = np.mean(X), np.mean(Y)
        length = ((X[0] - X[1]) ** 2 + (Y[0] - Y[1]) ** 2) ** 0.5
        angle = math.degrees(math.atan2(X[0] - X[1], Y[0] - Y[1]))
        poly = cv2.ellipse2Poly((int(mY), int(mX)), (int(length / 2), stickwidth), int(angle), 0, 360, 1)
        cv2.fillConvexPoly(img, poly, [int(float(c) * 0.6) for c in color])
    for k, color in zip(body, COLORS):
        if k[-1] < threshold:
            continue
        cv2.circle(img, (int(k[0]), int(k[1])), stickwidth, color, thickness=-1)
    img = draw_handpose_new(img, lhand, threshold)
    img = draw_handpose_new(img, rhand, threshold)
    return img


# ----------------------------------------------------------------------------------------------
# From utils.py padding_resize (same as animate.py WanAnimate.padding_resize) and get_face_bboxes
# (the second definition, which is the one in effect).
# ----------------------------------------------------------------------------------------------
def padding_resize(img_ori, height=512, width=512, padding_color=(0, 0, 0), interpolation=cv2.INTER_LINEAR):
    ori_height, ori_width, channel = img_ori.shape
    img_pad = np.zeros((height, width, channel))
    img_pad[:, :, :] = padding_color[:channel]
    if (ori_height / ori_width) > (height / width):
        new_width = int(height / ori_height * ori_width)
        img = cv2.resize(img_ori, (new_width, height), interpolation=interpolation)
        padding = int((width - new_width) / 2)
        img_pad[:, padding: padding + new_width, :] = img.reshape(height, new_width, channel)
    else:
        new_height = int(width / ori_width * ori_height)
        img = cv2.resize(img_ori, (width, new_height), interpolation=interpolation)
        padding = int((height - new_height) / 2)
        img_pad[padding: padding + new_height, :, :] = img.reshape(new_height, width, channel)
    return np.uint8(img_pad)


def get_face_bboxes(kp2ds, scale, image_shape):
    h, w = image_shape
    kp2ds_face = kp2ds.copy()[1:] * (w, h)  # drop index 0 (COCO-WB 22, right heel), keep the 68 face points
    min_x, min_y = np.min(kp2ds_face, axis=0)
    max_x, max_y = np.max(kp2ds_face, axis=0)
    initial_width = max_x - min_x
    initial_height = max_y - min_y
    expanded_area = initial_width * initial_height * scale
    new_width = np.sqrt(expanded_area * (initial_width / initial_height))
    new_height = np.sqrt(expanded_area * (initial_height / initial_width))
    delta_width = (new_width - initial_width) / 2
    delta_height = (new_height - initial_height) / 4
    expanded_min_x = max(min_x - delta_width, 0)
    expanded_max_x = min(max_x + delta_width, w)
    expanded_min_y = max(min_y - 3 * delta_height, 0)
    expanded_max_y = min(max_y + delta_height, h)
    return [int(expanded_min_x), int(expanded_max_x), int(expanded_min_y), int(expanded_max_y)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--width", type=int, required=True)
    ap.add_argument("--height", type=int, required=True)
    ap.add_argument("--fps", type=float, default=16, help="sampling fps; -1 = the video's own")
    ap.add_argument("--frames", type=int, required=True)
    ap.add_argument("--start", type=int, default=0, help="first source-video frame index")
    ap.add_argument("--models", default=os.path.join(HERE, "models"))
    ap.add_argument("--threshold", type=float, default=0.5, help="draw threshold (upstream 0.5)")
    a = ap.parse_args()
    if a.width % 16 or a.height % 16:
        print("warning: width/height not multiples of 16 (upstream resize_by_area uses divisor 16)")

    os.makedirs(os.path.join(a.out, "pose"), exist_ok=True)
    os.makedirs(os.path.join(a.out, "face"), exist_ok=True)

    # reference: animate.py prepare_source -> padding_resize(src_ref, H, W), INTER_LINEAR, black bars
    ref_bgr = cv2.imread(a.ref, cv2.IMREAD_COLOR)
    if ref_bgr is None:
        sys.exit(f"cannot read {a.ref}")
    # upstream picks the clip size from the reference instead: resize_by_area(ref, area, divisor=16), whose
    # calculate_new_size always raises (check_valid arity bug) so the fallback below is what runs (utils.py L140-149)
    rh, rw = ref_bgr.shape[:2]
    uh = math.sqrt(a.width * a.height / (rw / rh))
    print(f"ref {rw}x{rh}; upstream with --resolution_area {a.width} {a.height} would use "
          f"{int((a.width * a.height / uh) // 16 * 16)}x{int(uh // 16 * 16)}; using {a.width}x{a.height}")
    ref = padding_resize(ref_bgr[..., ::-1].copy(), a.height, a.width)
    cv2.imwrite(os.path.join(a.out, "ref.png"), ref[..., ::-1])

    frames, video_fps, idxs = read_frames(a.video, a.start, a.fps, a.frames)
    H0, W0 = frames[0].shape[:2]

    det = YoloX(os.path.join(a.models, "yolox_l.onnx"))
    pose = DWPose(os.path.join(a.models, "dw-ll_ucoco_384.onnx"))
    kp_all, boxes = [], []
    for i, f in enumerate(frames):
        bb = det(f)
        kp_all.append(pose(f, bb))
        boxes.append(bb.tolist())
        print(f"frame {i}: box {np.round(bb, 1).tolist()}", flush=True)
    metas = load_pose_metas_from_kp2ds_seq(kp_all, W0, H0)

    for i, (f, m) in enumerate(zip(frames, metas)):
        # face: process_pipepline.py L169-176, crop from the source-resolution frame, stretch to 512x512
        x1, x2, y1, y2 = get_face_bboxes(m["keypoints_face"][:, :2], 1.3, (H0, W0))
        face = cv2.resize(f[y1:y2, x1:x2], (512, 512))
        cv2.imwrite(os.path.join(a.out, "face", f"{i:04d}.png"), face[..., ::-1])
        # pose: process_pipepline.py L224-226, draw on a black canvas at source size, then letterbox to W x H
        body, lh, rh = to_pixels(m)
        canvas = np.zeros_like(f)
        canvas = draw_aapose_new(canvas, body, lh, rh, a.threshold)
        canvas = padding_resize(canvas, a.height, a.width)
        cv2.imwrite(os.path.join(a.out, "pose", f"{i:04d}.png"), canvas[..., ::-1])

    with open(os.path.join(a.out, "keypoints.json"), "w") as fp:
        json.dump({"source_size": [W0, H0], "video_fps": video_fps, "fps": a.fps, "indices": idxs,
                   "boxes_xyxy_score": boxes, "keypoints_133": [k.round(3).tolist() for k in kp_all]}, fp)
    sc = np.stack(kp_all)[:, :, 2]
    print("score ranges: body %.2f..%.2f  face %.2f..%.2f  hands %.2f..%.2f" % (
        sc[:, :23].min(), sc[:, :23].max(), sc[:, 23:91].min(), sc[:, 23:91].max(), sc[:, 91:].min(), sc[:, 91:].max()))
    print(f"wrote {len(frames)} pose + face frames, ref {a.width}x{a.height} to {a.out}")


if __name__ == "__main__":
    main()
