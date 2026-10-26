// Second target of the estate EventBridge rule: logs the whole event (correlation id included)
// so CloudWatch shows that it arrived. Also the target of the estate HTTP API (API Gateway v2, payload format 2.0,
// `ANY /log`): then it answers with a JSON document that says what it saw.
export const handler = async (event) => {
  const http = event?.version === "2.0" && event.requestContext?.http;
  const correlation = http
    ? event.headers?.["x-correlation-id"] ?? event.queryStringParameters?.correlation
    : event?.detail?.correlation;
  if (http) {
    // not the whole event: the request context carries the time of the request
    console.log(JSON.stringify({ msg: "estate http request received", correlation, method: http.method, path: http.path, bytes: (event.body ?? "").length }));
  } else {
    console.log(JSON.stringify({ msg: "estate event received", id: event?.detail?.id, correlation, event }));
  }
  if (http) {
    return {
      statusCode: 200,
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ok: true, correlation, method: http.method, path: http.path, bytes: (event.body ?? "").length }),
    };
  }
  return { ok: true, correlation };
};
