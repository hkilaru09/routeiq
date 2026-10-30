-- Two tenants backed by real public datasets (see replay/).
INSERT INTO tenants (id, name, default_sla_minutes) VALUES
    ('cfpb', 'CFPB Consumer Complaints', 1440),
    ('nyc311', 'NYC 311 Service Requests', 240);

-- Teams. Webhook targets point at the docker-compose webhook sink; email goes to Mailpit.
INSERT INTO teams (id, tenant_id, slug, name, email, webhook_url, is_default) VALUES
    ('11111111-0000-0000-0000-000000000001', 'cfpb', 'credit-reporting', 'Credit Reporting', 'credit-reporting@cfpb.routeiq.local', 'http://webhook-sink:8080/hooks/cfpb/credit-reporting', false),
    ('11111111-0000-0000-0000-000000000002', 'cfpb', 'debt-collection',  'Debt Collection',  'debt-collection@cfpb.routeiq.local',  'http://webhook-sink:8080/hooks/cfpb/debt-collection',  false),
    ('11111111-0000-0000-0000-000000000003', 'cfpb', 'mortgages',        'Mortgages',        'mortgages@cfpb.routeiq.local',        'http://webhook-sink:8080/hooks/cfpb/mortgages',        false),
    ('11111111-0000-0000-0000-000000000004', 'cfpb', 'banking',          'Banking & Cards',  'banking@cfpb.routeiq.local',          'http://webhook-sink:8080/hooks/cfpb/banking',          false),
    ('11111111-0000-0000-0000-000000000005', 'cfpb', 'general',          'General Intake',   'general@cfpb.routeiq.local',          'http://webhook-sink:8080/hooks/cfpb/general',          true),

    ('22222222-0000-0000-0000-000000000001', 'nyc311', 'noise',          'Noise (NYPD)',            'noise@nyc311.routeiq.local',          'http://webhook-sink:8080/hooks/nyc311/noise',          false),
    ('22222222-0000-0000-0000-000000000002', 'nyc311', 'streets',        'Streets (DOT)',           'streets@nyc311.routeiq.local',        'http://webhook-sink:8080/hooks/nyc311/streets',        false),
    ('22222222-0000-0000-0000-000000000003', 'nyc311', 'housing',        'Housing (HPD)',           'housing@nyc311.routeiq.local',        'http://webhook-sink:8080/hooks/nyc311/housing',        false),
    ('22222222-0000-0000-0000-000000000004', 'nyc311', 'sanitation',     'Sanitation (DSNY)',       'sanitation@nyc311.routeiq.local',     'http://webhook-sink:8080/hooks/nyc311/sanitation',     false),
    ('22222222-0000-0000-0000-000000000005', 'nyc311', 'general',        'General Intake',          'general@nyc311.routeiq.local',        'http://webhook-sink:8080/hooks/nyc311/general',        true);

-- Rules are evaluated in ascending priority; first match wins, otherwise the default team.
INSERT INTO routing_rules (id, tenant_id, name, priority, field, operator, match_value, team_id, sla_minutes) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'cfpb', 'Credit reports',      10, 'category', 'PREFIX',   'Credit reporting', '11111111-0000-0000-0000-000000000001', 720),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'cfpb', 'Debt collection',     20, 'category', 'EQUALS',   'Debt collection',  '11111111-0000-0000-0000-000000000002', 1440),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'cfpb', 'Mortgages',           30, 'category', 'CONTAINS', 'mortgage',         '11111111-0000-0000-0000-000000000003', 2880),
    ('aaaaaaaa-0000-0000-0000-000000000004', 'cfpb', 'Banking and cards',   40, 'category', 'REGEX',    '(checking|savings|credit card|prepaid card|money transfer)', '11111111-0000-0000-0000-000000000004', 1440),

    ('bbbbbbbb-0000-0000-0000-000000000001', 'nyc311', 'Noise complaints',  10, 'category',    'PREFIX', 'Noise',            '22222222-0000-0000-0000-000000000001', 120),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'nyc311', 'DOT agency',        20, 'attr.agency', 'EQUALS', 'DOT',              '22222222-0000-0000-0000-000000000002', 480),
    ('bbbbbbbb-0000-0000-0000-000000000003', 'nyc311', 'Heat and hot water',30, 'category',    'IN',     'HEAT/HOT WATER,PLUMBING,UNSANITARY CONDITION,PAINT/PLASTER,WATER LEAK', '22222222-0000-0000-0000-000000000003', 240),
    ('bbbbbbbb-0000-0000-0000-000000000004', 'nyc311', 'DSNY agency',       40, 'attr.agency', 'EQUALS', 'DSNY',             '22222222-0000-0000-0000-000000000004', 720);
