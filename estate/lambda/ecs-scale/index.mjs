// Last link of the estate chain, fired by the one-off schedule: scales the ECS service to the desired count and puts the
// value in the SSM parameter (the "config update"). Event = the schedule's input:
//   { cluster, service, desiredCount, parameter, correlation }
// A missing cluster or service is not an error (the real-AWS estate has no service: the CODE_DEPLOY one needs a load
// balancer); the result says `scaled: false` and why, and the parameter is still put.
import { ECSClient, UpdateServiceCommand } from "@aws-sdk/client-ecs";
import { SSMClient, PutParameterCommand } from "@aws-sdk/client-ssm";

const ecs = new ECSClient({});
const ssm = new SSMClient({});

const MISSING = new Set(["ServiceNotFoundException", "ClusterNotFoundException", "ServiceNotActiveException"]);

export const handler = async (event) => {
  const { cluster, service, desiredCount, parameter, correlation } = event ?? {};
  const count = Number(desiredCount);
  if (!Number.isInteger(count) || count < 0) throw new Error(`desiredCount must be a non-negative integer, got ${desiredCount}`);
  const result = { desiredCount: count, scaled: false, parameter, correlation };
  if (cluster && service) {
    try {
      const r = await ecs.send(new UpdateServiceCommand({ cluster, service, desiredCount: count }));
      result.scaled = true;
      result.service = r.service?.serviceName ?? service;
    } catch (e) {
      if (!MISSING.has(e?.name)) throw e;
      result.reason = e.name;
    }
  } else {
    result.reason = "no ECS service configured";
  }
  if (parameter) {
    await ssm.send(new PutParameterCommand({ Name: parameter, Value: String(count), Type: "String", Overwrite: true }));
    result.parameterPut = true;
  }
  console.log(JSON.stringify({ msg: "estate scale", ...result }));
  return result;
};
