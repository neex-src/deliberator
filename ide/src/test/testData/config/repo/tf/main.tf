locals {
  services = {
    q = {
      port        = 9000
      environment = { APP_AUTO = var.auto }
      secrets = {
        TPL_ONLY   = module.x.arn
        TABLE_NAME = local.ssm_arn["common/table-name"]
      }
    }
    ok = {
      port        = 9001
      environment = { APP_AUTO = var.auto }
      secrets = {
        TABLE_NAME = local.ssm_arn["common/table-name"]
        API_KEY    = aws_secretsmanager_secret.api_key.arn
      }
    }
  }
}
