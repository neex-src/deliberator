terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

# credentials come from AWS_PROFILE / AWS_ACCESS_KEY_ID env, never from this repo
provider "aws" {
  region = "eu-central-1"
}

data "aws_caller_identity" "me" {}

output "account_id" {
  value = data.aws_caller_identity.me.account_id
}

resource "aws_budgets_budget" "monthly" {
  name         = "a sandbox AWS account-monthly"
  budget_type  = "COST"
  limit_amount = "5"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }
}

variable "alert_email" {
  type = string
}
