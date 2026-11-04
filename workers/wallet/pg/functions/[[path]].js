import worker from "./_api.js";
export const onRequest = (ctx) => worker.fetch(ctx.request, ctx.env);
