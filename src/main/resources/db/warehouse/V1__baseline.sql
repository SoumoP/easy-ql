-- V1__baseline.sql
-- Baseline migration. Records project metadata so the migration pipeline is
-- proven end-to-end before the real warehouse schema lands in Plan 2.

create table _easyql_meta (
    key         text primary key,
    value       text not null,
    created_at  timestamptz not null default now()
);

insert into _easyql_meta (key, value) values
    ('schema_version_baseline', 'V1'),
    ('project',                 'easy-ql'),
    ('plan_origin',             'plan-1-foundation');
