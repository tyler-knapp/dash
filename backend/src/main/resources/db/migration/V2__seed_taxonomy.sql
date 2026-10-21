-- Seed taxonomy, identical to reference/prototype.html (SPEC §4).
INSERT INTO category (name, kind, sort_order) VALUES
    ('Housing',          'spend',    1),
    ('Food',             'spend',    2),
    ('Transportation',   'spend',    3),
    ('Health & Fitness', 'spend',    4),
    ('Subscriptions',    'spend',    5),
    ('Lifestyle',        'spend',    6),
    ('Side Hustles',     'spend',    7),
    ('Income',           'income',   8),
    ('Transfer',         'transfer', 9);

INSERT INTO subcategory (category_name, name, sort_order) VALUES
    ('Housing', 'Rent', 1), ('Housing', 'Electric', 2), ('Housing', 'Internet', 3), ('Housing', 'Renters Insurance', 4),
    ('Food', 'Groceries', 1), ('Food', 'Restaurants', 2), ('Food', 'Coffee', 3),
    ('Transportation', 'Gas', 1), ('Transportation', 'Car Insurance', 2), ('Transportation', 'Maintenance', 3), ('Transportation', 'Parking', 4),
    ('Health & Fitness', 'Gym', 1), ('Health & Fitness', 'Race Fees', 2), ('Health & Fitness', 'Running Gear', 3), ('Health & Fitness', 'Supplements', 4), ('Health & Fitness', 'Medical', 5),
    ('Subscriptions', 'Software & AI', 1), ('Subscriptions', 'Streaming', 2), ('Subscriptions', 'Research Tools', 3),
    ('Lifestyle', 'Clothing', 1), ('Lifestyle', 'Haircuts', 2), ('Lifestyle', 'Events', 3), ('Lifestyle', 'Travel', 4), ('Lifestyle', 'Gifts', 5),
    ('Side Hustles', 'Reselling Inventory', 1), ('Side Hustles', 'YouTube Gear', 2),
    ('Income', 'Salary', 1), ('Income', 'Interest', 2), ('Income', 'Reselling Sales', 3),
    ('Transfer', 'Card Payment', 1), ('Transfer', 'Account Transfer', 2), ('Transfer', 'Retirement Contribution', 3);
