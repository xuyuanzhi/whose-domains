-- Read-only export for editorial review. No article mutations.
-- Client output: --batch --raw --skip-column-names; save as UTF-8 JSONL.
SELECT JSON_OBJECT(
  'id', ID, 'slug', SLUG, 'title', TITLE, 'summary', SUMMARY,
  'content', CONTENT, 'author', AUTHOR, 'aiGenerated', AI_GENERATED,
  'metaTitle', META_TITLE, 'metaDescription', META_DESCRIPTION,
  'status', STATUS, 'publishDate', PUBLISH_DATE
) AS article_json
FROM WEB_BLOG_POST
WHERE DELETED = 0 AND ID IN (
  '9ca91492f7204620',
  '2d309ae665e647b9',
  '56aa534f373449c5',
  '47a4051d54d34d3c',
  'fe5289e706fe43d1',
  'blog003',
  'blog001',
  '16d4adce62884400',
  'blog007',
  'blog004'
)
ORDER BY ID;
