// A second pack, delivered as a ZIP -- what a downloaded plugin actually is.
//
// It exists to prove that packs compose: this one runs after
// com.example.center-square in the same graph, from a different directory,
// installed by a different route, with neither knowing about the other.
//
// Longest edge to `edge`, aspect preserved.

__nm.register('com.example.thumbnail:Thumbnail', {
  run: function (ctx, inputs, widgets) {
    var edge = parseInt(widgets.edge, 10);
    if (!(edge > 0)) throw new Error('edge must be > 0, got ' + widgets.edge);

    var info = ctx.host('image.info', { image: inputs.image });
    var longest = Math.max(info.width, info.height);
    var k = edge / longest;

    return ctx.host('image.resize', {
      image: inputs.image,
      width:  Math.max(1, Math.round(info.width  * k)),
      height: Math.max(1, Math.round(info.height * k))
    });
  }
});
