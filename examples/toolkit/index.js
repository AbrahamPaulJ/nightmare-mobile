// Two nodes in one pack, composing the host's image ops.
//
// Framed uses image.new + image.composite; Desaturate uses image.grayscale +
// image.blend. Between them they cover the whole Tier 0 surface that is not
// resize/crop, and they are the first pack to declare more than one node --
// which is what a real "pack" looks like.

__nm.register('com.example.toolkit:Framed', {
  run: function (ctx, inputs, widgets) {
    var m = parseInt(widgets.margin, 10);
    if (!(m >= 0)) throw new Error('margin must be >= 0, got ' + widgets.margin);

    var info = ctx.host('image.info', { image: inputs.image });
    var board = ctx.host('image.new', {
      width:  info.width  + 2 * m,
      height: info.height + 2 * m,
      color:  widgets.color
    });

    return ctx.host('image.composite', {
      base: board.image,
      overlay: inputs.image,
      x: m,
      y: m
    });
  }
});

__nm.register('com.example.toolkit:Desaturate', {
  run: function (ctx, inputs, widgets) {
    var amount = parseFloat(widgets.amount);
    if (!(amount >= 0 && amount <= 1)) {
      throw new Error('amount must be 0..1, got ' + widgets.amount);
    }

    var grey = ctx.host('image.grayscale', { image: inputs.image });

    // alpha is how much of `b` shows, so `amount` of the grey version.
    return ctx.host('image.blend', {
      a: inputs.image,
      b: grey.image,
      alpha: amount
    });
  }
});
