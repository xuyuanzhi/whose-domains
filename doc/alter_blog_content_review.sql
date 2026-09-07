-- Run before deploying the blog content review release.
-- Additive and idempotent; does not modify or delete any articles.
CREATE TABLE IF NOT EXISTS `WEB_BLOG_EDITORIAL_LOCK` (
  `ID` tinyint NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB;
INSERT IGNORE INTO `WEB_BLOG_EDITORIAL_LOCK` (`ID`) VALUES (1);
