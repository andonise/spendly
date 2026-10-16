-- System seed for the rules layer: well-known Romanian merchants and chains, written from general knowledge
-- (not taken from any evaluation dataset). Patterns are match keys (see V2: merchant_aliases.pattern).
-- Embeddings are filled in lazily by the backend. IDs are deterministic so the seed is reproducible.

CREATE TEMPORARY TABLE seed_merchants (name varchar(120), match_key varchar(120), category varchar(30), patterns text[]);

INSERT INTO seed_merchants (name, match_key, category, patterns) VALUES
    -- groceries
    ('Kaufland',       'KAUFLAND',       'GROCERIES',     ARRAY['KAUFLAND']),
    ('Lidl',           'LIDL',           'GROCERIES',     ARRAY['LIDL']),
    ('Carrefour',      'CARREFOUR',      'GROCERIES',     ARRAY['CARREFOUR']),
    ('Mega Image',     'MEGA IMAGE',     'GROCERIES',     ARRAY['MEGA IMAGE']),
    ('Profi',          'PROFI',          'GROCERIES',     ARRAY['PROFI']),
    ('Auchan',         'AUCHAN',         'GROCERIES',     ARRAY['AUCHAN']),
    ('Penny',          'PENNY',          'GROCERIES',     ARRAY['PENNY MARKET', 'PENNY']),
    ('Selgros',        'SELGROS',        'GROCERIES',     ARRAY['SELGROS']),
    -- restaurants, cafes, food delivery
    ('McDonald''s',    'MCDONALD S',     'RESTAURANTS',   ARRAY['MCDONALD S', 'MCDONALDS', 'MC DONALDS']),
    ('KFC',            'KFC',            'RESTAURANTS',   ARRAY['KFC']),
    ('Starbucks',      'STARBUCKS',      'RESTAURANTS',   ARRAY['STARBUCKS']),
    ('Burger King',    'BURGER KING',    'RESTAURANTS',   ARRAY['BURGER KING']),
    ('Pizza Hut',      'PIZZA HUT',      'RESTAURANTS',   ARRAY['PIZZA HUT']),
    ('Glovo',          'GLOVO',          'RESTAURANTS',   ARRAY['GLOVO']),
    ('Tazz',           'TAZZ',           'RESTAURANTS',   ARRAY['TAZZ']),
    ('Uber Eats',      'UBER EATS',      'RESTAURANTS',   ARRAY['UBER EATS']),
    ('Bolt Food',      'BOLT FOOD',      'RESTAURANTS',   ARRAY['BOLT FOOD']),
    -- fuel
    ('OMV',            'OMV',            'FUEL',          ARRAY['OMV']),
    ('Petrom',         'PETROM',         'FUEL',          ARRAY['PETROM']),
    ('Rompetrol',      'ROMPETROL',      'FUEL',          ARRAY['ROMPETROL']),
    ('Lukoil',         'LUKOIL',         'FUEL',          ARRAY['LUKOIL']),
    ('Socar',          'SOCAR',          'FUEL',          ARRAY['SOCAR']),
    -- transport
    ('Uber',           'UBER',           'TRANSPORT',     ARRAY['UBER']),
    ('Bolt',           'BOLT',           'TRANSPORT',     ARRAY['BOLT']),
    ('CFR Calatori',   'CFR CALATORI',   'TRANSPORT',     ARRAY['CFR CALATORI']),
    ('Metrorex',       'METROREX',       'TRANSPORT',     ARRAY['METROREX']),
    -- utilities, telecom
    ('Enel',           'ENEL',           'UTILITIES',     ARRAY['ENEL']),
    ('E.ON',           'E ON',           'UTILITIES',     ARRAY['E ON', 'EON ENERGIE']),
    ('Engie',          'ENGIE',          'UTILITIES',     ARRAY['ENGIE']),
    ('Electrica',      'ELECTRICA',      'UTILITIES',     ARRAY['ELECTRICA FURNIZARE', 'ELECTRICA']),
    ('Digi',           'DIGI',           'UTILITIES',     ARRAY['DIGI', 'RCS RDS']),
    ('Orange',         'ORANGE',         'UTILITIES',     ARRAY['ORANGE']),
    ('Vodafone',       'VODAFONE',       'UTILITIES',     ARRAY['VODAFONE']),
    ('Telekom',        'TELEKOM',        'UTILITIES',     ARRAY['TELEKOM']),
    -- shopping
    ('eMAG',           'EMAG',           'SHOPPING',      ARRAY['EMAG']),
    ('Altex',          'ALTEX',          'SHOPPING',      ARRAY['ALTEX']),
    ('Flanco',         'FLANCO',         'SHOPPING',      ARRAY['FLANCO']),
    ('Dedeman',        'DEDEMAN',        'SHOPPING',      ARRAY['DEDEMAN']),
    ('IKEA',           'IKEA',           'SHOPPING',      ARRAY['IKEA']),
    ('Leroy Merlin',   'LEROY MERLIN',   'SHOPPING',      ARRAY['LEROY MERLIN']),
    ('Zara',           'ZARA',           'SHOPPING',      ARRAY['ZARA']),
    ('Pepco',          'PEPCO',          'SHOPPING',      ARRAY['PEPCO']),
    -- health
    ('Catena',         'CATENA',         'HEALTH',        ARRAY['CATENA']),
    ('Dr. Max',        'DR MAX',         'HEALTH',        ARRAY['DR MAX']),
    ('Sensiblu',       'SENSIBLU',       'HEALTH',        ARRAY['SENSIBLU']),
    ('Regina Maria',   'REGINA MARIA',   'HEALTH',        ARRAY['REGINA MARIA']),
    ('MedLife',        'MEDLIFE',        'HEALTH',        ARRAY['MEDLIFE']),
    -- subscriptions
    ('Netflix',        'NETFLIX',        'SUBSCRIPTIONS', ARRAY['NETFLIX']),
    ('Spotify',        'SPOTIFY',        'SUBSCRIPTIONS', ARRAY['SPOTIFY']),
    ('HBO Max',        'HBO MAX',        'SUBSCRIPTIONS', ARRAY['HBO MAX']),
    ('YouTube Premium','YOUTUBE PREMIUM','SUBSCRIPTIONS', ARRAY['YOUTUBE PREMIUM']),
    -- travel
    ('Booking.com',    'BOOKING COM',    'TRAVEL',        ARRAY['BOOKING COM']),
    ('Airbnb',         'AIRBNB',         'TRAVEL',        ARRAY['AIRBNB']),
    ('Wizz Air',       'WIZZ AIR',       'TRAVEL',        ARRAY['WIZZ AIR', 'WIZZAIR']),
    -- entertainment
    ('Cinema City',    'CINEMA CITY',    'ENTERTAINMENT', ARRAY['CINEMA CITY']),
    -- insurance
    ('Allianz-Tiriac', 'ALLIANZ TIRIAC', 'INSURANCE',     ARRAY['ALLIANZ TIRIAC', 'ALLIANZ']),
    ('Groupama',       'GROUPAMA',       'INSURANCE',     ARRAY['GROUPAMA']),
    ('Generali',       'GENERALI',       'INSURANCE',     ARRAY['GENERALI']),
    -- taxes
    ('ANAF',           'ANAF',           'TAXES',         ARRAY['ANAF']),
    ('Ghiseul.ro',     'GHISEUL RO',     'TAXES',         ARRAY['GHISEUL RO']),
    -- sport
    ('World Class',    'WORLD CLASS',    'SPORT',         ARRAY['WORLD CLASS']);

INSERT INTO merchants (id, normalized_name, match_key, default_category)
SELECT md5('seed-merchant:' || match_key)::uuid, name, match_key, category FROM seed_merchants;

INSERT INTO merchant_aliases (id, pattern, merchant_id, created_by)
SELECT md5('seed-alias:' || p)::uuid, p, md5('seed-merchant:' || s.match_key)::uuid, 'SYSTEM'
FROM seed_merchants s, unnest(s.patterns) AS p;

DROP TABLE seed_merchants;
