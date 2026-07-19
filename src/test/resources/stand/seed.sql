-- Stand-mode seed: brings the stand's DB to a known baseline BEFORE a test class runs.
-- Run by StandDataTestExecutionListener#beforeTestClass (only when tests.mode=stand).
--
-- Two jobs:
--   1) remove anything left from a previous test run (identical logic to cleanup.sql) so an
--      aborted/crashed run — where afterTestClass never fired — can't pollute this one;
--   2) guarantee the reference category + breed the tests address by fixed UUID exist. The
--      stand's own Liquibase already seeds these; the idempotent upsert makes stand mode
--      independent of the stand's migration state and is a no-op when they already exist.
--
-- Test-created rows are identified by the @example.com email domain used by
-- IntegrationTestBase.createUser. Deletes are in FK dependency order: children before parents.

DELETE FROM reviews        WHERE author_id    IN (SELECT id FROM users WHERE email LIKE '%@example.com')
                                 OR recipient_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM bookings       WHERE buyer_id  IN (SELECT id FROM users WHERE email LIKE '%@example.com')
                                 OR seller_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM favorites      WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM subscriptions  WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM messages       WHERE sender_id   IN (SELECT id FROM users WHERE email LIKE '%@example.com')
                                 OR receiver_id  IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM listing_images WHERE listing_id IN (SELECT id FROM listings WHERE seller_id IN (SELECT id FROM users WHERE email LIKE '%@example.com'));
DELETE FROM listings       WHERE seller_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM profiles       WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@example.com');
DELETE FROM users          WHERE email LIKE '%@example.com';

INSERT INTO categories (id, name_ru, name_en, slug)
VALUES ('11111111-1111-1111-1111-111111111111', 'Собаки', 'Dogs', 'dogs')
ON CONFLICT (id) DO NOTHING;

INSERT INTO breeds (id, category_id, name_ru, name_en)
VALUES ('10000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Лабрадор-ретривер', 'Labrador Retriever')
ON CONFLICT (id) DO NOTHING;