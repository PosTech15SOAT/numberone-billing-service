terraform {
  required_version = ">= 1.15.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.61.0"
    }
  }
}

provider "aws" {}

data "aws_secretsmanager_secret" "database" {
  name = "numberone-billing-database"
}
