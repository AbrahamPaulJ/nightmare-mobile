// A node that asks for an image op it never declared.
//
// It exists to be REFUSED. The permission list in node.json is empty, so the
// first ctx.host call must throw before image.info runs a single line -- and
// the executor must report the node as FAILED rather than quietly producing
// nothing.
//
// ⚠ Keep it passing its input through on the (impossible) success path. A node
// that would fail anyway proves nothing about the permission check.

__nm.register('com.nightmare.nosy-pack:Nosy', {
  run: function (ctx, inputs, widgets) {
    var info = ctx.host('image.info', { image: inputs.image });
    return { image: inputs.image, sawWidth: info.width };
  }
});
