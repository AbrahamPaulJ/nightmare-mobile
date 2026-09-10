// Inpainting, written by a contributor.
//
// This is the tier ladder's whole argument in one file: latent blending needs
// no 9-channel UNet and no conversion pipeline, so a node that composites two
// renders is Tier 0 -- a manifest and some JavaScript, no app release.
// (../LocalDream/docs/INPAINT.md section 1.)
//
// Note what never crosses into JS: pixels, latents, base64. The mask is an
// image handle, the latents are backend handles, and the host moves the bytes.

__nm.register('com.example.latent-mix:HalfMask', {
  run: function (ctx, inputs, widgets) {
    var size = parseInt(widgets.size, 10);
    var coverage = parseFloat(widgets.coverage);
    if (!(size > 0)) throw new Error('size must be > 0, got ' + widgets.size);
    if (!(coverage >= 0 && coverage <= 1)) {
      throw new Error('coverage must be 0..1, got ' + widgets.coverage);
    }

    // Black keeps A; the white band is where B shows through.
    var black = ctx.host('image.new', { width: size, height: size, color: '#000000' });
    var white = ctx.host('image.new', {
      width: Math.max(1, Math.round(size * coverage)),
      height: size,
      color: '#ffffff'
    });

    return ctx.host('image.composite', {
      base: black.image,
      overlay: white.image,
      x: 0,
      y: 0
    });
  }
});

__nm.register('com.example.latent-mix:LatentMix', {
  run: function (ctx, inputs, widgets) {
    // mask = 1 takes b. Same convention as everywhere else in the runtime.
    return ctx.host('latent.blend', {
      a: inputs.a,
      b: inputs.b,
      mask: inputs.mask
    });
  }
});
