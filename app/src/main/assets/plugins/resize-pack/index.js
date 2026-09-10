// The first Tier 0 node: pure data, no model, no backend.
//
// It is deliberately unremarkable -- that is the point. A contributor writes
// this much JavaScript, ships it beside a manifest, and the executor treats the
// result exactly like a built-in node: cached, keyed, invalidated when its
// inputs or widgets change.
//
// Note what it never touches: pixels. `inputs.image` is a handle, and
// `ctx.host` gives it back another one. Marshalling the array would cost more
// than the resize.

__nm.register('com.nightmare.resize-pack:Resize', {
  run: function (ctx, inputs, widgets) {
    var scale = parseFloat(widgets.scale);
    if (!(scale > 0)) throw new Error('scale must be > 0, got ' + widgets.scale);

    var info = ctx.host('image.info', { image: inputs.image });
    var w = Math.max(1, Math.round(info.width * scale));
    var h = Math.max(1, Math.round(info.height * scale));

    return ctx.host('image.resize', { image: inputs.image, width: w, height: h });
  }
});
