// A node the app has never seen.
//
// This pack is NOT in the APK. It is pushed to the device's plugin directory
// and loaded at runtime, which is the actual Tier 0 claim: a contributor adds a
// node with no app release, no toolchain and no compile step
// (docs/ARCHITECTURE.md section 2).
//
// It crops the largest centred square and scales it to `size`, so it composes
// two host ops rather than wrapping one -- a node with real logic in it, not a
// rename of image.resize.

__nm.register('com.example.center-square:CenterSquare', {
  run: function (ctx, inputs, widgets) {
    var size = parseInt(widgets.size, 10);
    if (!(size > 0)) throw new Error('size must be > 0, got ' + widgets.size);

    var info = ctx.host('image.info', { image: inputs.image });
    var side = Math.min(info.width, info.height);

    var square = ctx.host('image.crop', {
      image: inputs.image,
      x: Math.floor((info.width  - side) / 2),
      y: Math.floor((info.height - side) / 2),
      width: side,
      height: side
    });

    return ctx.host('image.resize', { image: square.image, width: size, height: size });
  }
});
