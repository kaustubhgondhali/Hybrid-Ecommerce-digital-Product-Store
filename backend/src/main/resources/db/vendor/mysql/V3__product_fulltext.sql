-- MySQL only (loaded from db/vendor/{vendor}): relevance-ranked product search.
CREATE FULLTEXT INDEX ft_products_search ON products (title, summary, description, brand, focus_keyword);
