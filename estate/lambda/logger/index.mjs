// Second target of the estate EventBridge rule: logs the whole event (correlation id included)
// so CloudWatch shows that it arrived.
export const handler = async (event) => {
  const correlation = event?.detail?.correlation;
  console.log(JSON.stringify({ msg: "estate event received", id: event?.detail?.id, correlation, event }));
  return { ok: true, correlation };
};
