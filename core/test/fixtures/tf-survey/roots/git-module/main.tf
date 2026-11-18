terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "eu-central-1"
}

module "shared" {
  source = "git::https://127.0.0.1:9/nobody/terraform-shared.git?ref=v1.2.0"
}

resource "aws_sqs_queue" "plain" {
  name = "svy-git-module"
}
