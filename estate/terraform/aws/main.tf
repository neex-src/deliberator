# The estate on a real account. Never applied from this repository: `terraform validate` only.
# Credentials would come from AWS_PROFILE / the environment, never from files in the repo.
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = "eu-central-1"
}
