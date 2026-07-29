CREATE INDEX profile__props__newsletter1__idx ON profile (email) WHERE props->>'~:newsletter-news' = 'true';
CREATE INDEX profile__props__newsletter2__idx ON profile (email) WHERE props->>'~:newsletter-updates' = 'true';
