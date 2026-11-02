-- Upgrade the legacy promotional template without changing monetary allowances,
-- account validity, deployment capabilities or existing member policies.
update model_promotion_policy set max_output_tokens=262144,
 concurrency_limit=32,calls_per_minute=600,version=version+1
 where max_output_tokens=2048 and concurrency_limit=1 and calls_per_minute=5;
