-- Stand-mode cleanup: removes everything the test class just created, AFTER it runs.
-- Run by StandDataTestExecutionListener#afterTestClass (only when tests.mode=stand), even if
-- the class failed, so the stand is left clean for the next run.
--
-- Same scope and order as seed.sql: every row owned by a test user (@example.com email),
-- children deleted before parents to satisfy the NOT-NULL / no-cascade foreign keys.
-- Reference data (categories, breeds) is left untouched.

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