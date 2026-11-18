terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    datadog = {
      source = "DataDog/datadog"
    }
  }
}

provider "aws" {
  region = "eu-central-1"
}

provider "datadog" {
  validate = false
}

resource "aws_sqs_queue" "alerts" {
  name = "svy-other-provider-alerts"
}

resource "datadog_monitor" "queue_depth" {
  name    = "queue depth"
  type    = "metric alert"
  message = "the queue is deep: ${aws_sqs_queue.alerts.name}"
  query   = "avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible{*} > 100"
}
