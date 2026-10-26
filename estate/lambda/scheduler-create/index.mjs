// First link of the estate chain: creates a one-off EventBridge Scheduler schedule that, at the given time, invokes the
// ecs-scale Lambda. Called directly (Invoke) or through the HTTP API (POST /schedule, JSON body).
//   request  { delaySeconds?: 60, desiredCount?: 2, correlation?: "...", name?: "..." }
//   response { schedule, group, at, target, input }  (HTTP API: 202 with the same JSON)
// Configuration (environment): SCHEDULE_GROUP, SCALE_FUNCTION_ARN, SCHEDULER_ROLE_ARN, ECS_CLUSTER, ECS_SERVICE, PARAMETER_NAME
import { SchedulerClient, CreateScheduleCommand } from "@aws-sdk/client-scheduler";

const scheduler = new SchedulerClient({});

const body = (event) => {
  if (event?.version === "2.0" && event.requestContext?.http) {
    const raw = event.isBase64Encoded ? Buffer.from(event.body ?? "", "base64").toString() : event.body;
    return raw ? JSON.parse(raw) : {};
  }
  return event ?? {};
};

// at(yyyy-mm-ddThh:mm:ss), UTC
const at = (ms) => `at(${new Date(ms).toISOString().slice(0, 19)})`;

export const handler = async (event) => {
  const apiCall = event?.version === "2.0" && !!event.requestContext?.http;
  try {
    const req = body(event);
    const delay = Math.min(Math.max(Number(req.delaySeconds ?? 60), 1), 3600);
    const desiredCount = Number(req.desiredCount ?? 1);
    if (!Number.isInteger(desiredCount) || desiredCount < 0 || desiredCount > 10) {
      throw Object.assign(new Error("desiredCount must be an integer 0..10"), { status: 400 });
    }
    const correlation = req.correlation ?? event?.headers?.["x-correlation-id"] ?? "none";
    const name = String(req.name ?? `once-${Date.now()}`).replace(/[^A-Za-z0-9_.-]/g, "-").slice(0, 64);
    const group = process.env.SCHEDULE_GROUP;
    const input = {
      cluster: process.env.ECS_CLUSTER ?? "",
      service: process.env.ECS_SERVICE ?? "",
      desiredCount,
      parameter: process.env.PARAMETER_NAME,
      correlation,
    };
    const expression = at(Date.now() + delay * 1000);
    await scheduler.send(new CreateScheduleCommand({
      Name: name,
      GroupName: group,
      ScheduleExpression: expression,
      ScheduleExpressionTimezone: "UTC",
      FlexibleTimeWindow: { Mode: "OFF" },
      ActionAfterCompletion: "DELETE",
      Target: { Arn: process.env.SCALE_FUNCTION_ARN, RoleArn: process.env.SCHEDULER_ROLE_ARN, Input: JSON.stringify(input) },
    }));
    const result = { schedule: name, group, at: expression, target: process.env.SCALE_FUNCTION_ARN, input };
    console.log(JSON.stringify({ msg: "estate schedule created", correlation, schedule: name, group, at: expression, desiredCount }));
    return apiCall
      ? { statusCode: 202, headers: { "content-type": "application/json" }, body: JSON.stringify(result) }
      : result;
  } catch (e) {
    console.log(JSON.stringify({ msg: "estate schedule failed", error: String(e?.message ?? e), name: e?.name }));
    if (apiCall) {
      return { statusCode: e.status ?? 500, headers: { "content-type": "application/json" }, body: JSON.stringify({ error: String(e?.message ?? e) }) };
    }
    throw e;
  }
};
