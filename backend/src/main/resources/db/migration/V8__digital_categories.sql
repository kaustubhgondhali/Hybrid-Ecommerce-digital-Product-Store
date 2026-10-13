-- Elaatech (DIGITAL) category structure: 30 categories ordered by earning potential, with descriptions for shoppers
-- and search engines. Existing demo categories "software" and "courses" are upgraded in place (only while they still
-- carry the demo text); every other category is only added if its slug is free. Categories without products stay out
-- of the storefront menu and the sitemap until products are added. Portable SQL (MySQL 8 and H2).

UPDATE categories SET name = 'Software & SaaS', sort_order = 40,
       description = 'Business software for CRM, accounting, project management and HR, usually billed monthly or yearly.',
       meta_description = 'CRM, accounting, project management and HR software for small businesses. Compare plans and find the latest SaaS deals.'
 WHERE storefront = 'DIGITAL' AND slug = 'software' AND (description IS NULL OR description LIKE 'Demo category%');

UPDATE categories SET name = 'Online Courses', sort_order = 30,
       description = 'Courses in coding, digital marketing, AI, trading, finance, fitness and more, from leading learning platforms.',
       meta_description = 'Online courses in coding, digital marketing, AI, finance and fitness. Compare top learning platforms and find the latest course deals.'
 WHERE storefront = 'DIGITAL' AND slug = 'courses' AND (description IS NULL OR description LIKE 'Demo category%');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'AI Tools', 'ai-tools', 10, TRUE,
       'AI writing assistants, image generators, AI video editors and chatbots for work, study and content creation.',
       'Hand-picked AI tools for writing, image generation, video editing and chatbots, with the latest plans and deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'ai-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Web Hosting & Website Tools', 'website-tools', 20, TRUE,
       'Web hosting, domain names, WordPress themes and plugins to build and run a fast, secure website.',
       'Web hosting, domain names, WordPress themes and plugins. Compare plans and find the latest website deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'website-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Online Courses', 'courses', 30, TRUE,
       'Courses in coding, digital marketing, AI, trading, finance, fitness and more, from leading learning platforms.',
       'Online courses in coding, digital marketing, AI, finance and fitness. Compare top learning platforms and find the latest course deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'courses');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Software & SaaS', 'software', 40, TRUE,
       'Business software for CRM, accounting, project management and HR, usually billed monthly or yearly.',
       'CRM, accounting, project management and HR software for small businesses. Compare plans and find the latest SaaS deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'software');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'VPN & Cybersecurity', 'cybersecurity', 50, TRUE,
       'VPNs, password managers and antivirus software to keep your devices, accounts and data safe.',
       'VPNs, password managers and antivirus software. Compare security tools and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'cybersecurity');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Digital Marketing Tools', 'digital-marketing-tools', 60, TRUE,
       'SEO, email marketing and social media scheduling tools to grow traffic, leads and sales.',
       'SEO, email marketing and social media scheduling tools. Compare plans and find the latest marketing software deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'digital-marketing-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'AI Automation & Agents', 'ai-automation', 70, TRUE,
       'AI agents, workflow automation platforms and chatbot builders that take repetitive work off your hands.',
       'AI agents, workflow automation and chatbot builders for businesses. Compare tools and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'ai-automation');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'E-commerce Tools', 'ecommerce-tools', 80, TRUE,
       'Shopify apps, store themes and dropshipping tools for starting and growing an online store.',
       'Shopify apps, store themes and dropshipping tools. Compare e-commerce software and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'ecommerce-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Cloud & Productivity', 'cloud-productivity', 90, TRUE,
       'Cloud storage, office software and automation tools to get more done on any device.',
       'Cloud storage, office suites and productivity tools. Compare plans and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'cloud-productivity');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Design Assets & Templates', 'design-assets', 100, TRUE,
       'Canva templates, fonts, graphics and UI kits for social media, presentations, apps and websites.',
       'Canva templates, fonts, graphics and UI kits. Find ready-made design assets and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'design-assets');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Business Templates', 'business-templates', 110, TRUE,
       'Excel sheets, Notion templates and business plans that save hours of setup work.',
       'Excel, Notion and business plan templates for founders and small teams. Find ready-to-use templates.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'business-templates');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'E-books & Guides', 'ebooks', 120, TRUE,
       'E-books and practical guides on business, self-help, education, recipes and more.',
       'E-books and guides on business, self-help, education and cooking. Find popular titles and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'ebooks');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Online Certifications', 'certifications', 130, TRUE,
       'Professional certifications and skill-based training that strengthen your CV.',
       'Professional certifications and skill-based training programmes. Compare providers and find the latest offers.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'certifications');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Language Learning', 'language-learning', 140, TRUE,
       'Language courses, apps and study materials for spoken English, foreign languages and exams.',
       'Language learning courses, apps and study materials. Compare options and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'language-learning');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Finance & Tax Tools', 'finance-tools', 150, TRUE,
       'Budgeting software, tax filing tools and financial education to manage your money better.',
       'Budgeting apps, tax filing tools and personal finance courses. Compare options and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'finance-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Trading & Investing Education', 'trading-education', 160, TRUE,
       'Stock market courses, trading tools and market analysis software for learning to invest. For education only, not investment advice.',
       'Stock market and trading courses, tools and analysis software for learners. Education only, not investment advice.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'trading-education');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Stock Market Research Tools', 'stock-market-tools', 170, TRUE,
       'Stock screeners, charting platforms and financial data tools for research. For education only, not investment advice.',
       'Stock screeners, charting platforms and market data tools. Compare research tools. Not investment advice.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'stock-market-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Webinars & Workshops', 'webinars-workshops', 180, TRUE,
       'Paid workshops, masterclasses and live training sessions on business, tech and creative skills.',
       'Paid workshops, masterclasses and live online training. Find upcoming sessions and the latest offers.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'webinars-workshops');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Resume & Career', 'resume-career', 190, TRUE,
       'Resume templates, interview courses and job-preparation tools to help you land your next role.',
       'Resume templates, interview preparation courses and career tools. Find what you need for your next job.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'resume-career');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Freelancing Resources', 'freelancing', 200, TRUE,
       'Proposal templates, portfolio themes and skill courses for freelancers and independent professionals.',
       'Proposal templates, portfolio themes and skill courses for freelancers. Find resources and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'freelancing');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Stock Video, Music & SFX', 'stock-media', 210, TRUE,
       'Stock footage, music, sound effects and editing presets for videos, reels and podcasts.',
       'Stock video, music, sound effects and editing presets for creators. Compare libraries and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'stock-media');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Music & Creator Tools', 'creator-tools', 220, TRUE,
       'DAWs, audio plugins and creator software for music production, streaming and podcasting.',
       'DAWs, audio plugins and creator software for music, streaming and podcasts. Find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'creator-tools');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Photography Presets & LUTs', 'photography', 230, TRUE,
       'Lightroom presets, LUTs and photo editing courses for better photos in less time.',
       'Lightroom presets, LUTs and photo editing courses. Find popular packs and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'photography');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Digital Art & 3D Assets', 'digital-art-3d', 240, TRUE,
       'Brushes, 3D models, textures and animations for artists, designers and game makers.',
       'Digital brushes, 3D models, textures and animations. Find asset packs and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'digital-art-3d');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Printables & Planners', 'printables', 250, TRUE,
       'Printable planners, calendars, worksheets and habit trackers to download and print at home.',
       'Printable planners, calendars, worksheets and trackers. Download, print and get organised.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'printables');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Legal & Compliance Templates', 'legal-templates', 260, TRUE,
       'Contract, policy and business document templates. Have important documents checked by a lawyer before use.',
       'Contract, policy and business document templates for small businesses. Review with a lawyer before use.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'legal-templates');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Health & Fitness Programs', 'health-fitness', 270, TRUE,
       'Workout plans, yoga courses and meal-planning guides. Check with a doctor before starting a new programme.',
       'Workout plans, yoga courses and meal-planning guides. Find programmes and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'health-fitness');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Memberships & Subscriptions', 'memberships', 280, TRUE,
       'Premium communities, learning platforms and subscriptions with ongoing access.',
       'Premium communities, learning platforms and subscription services. Compare plans and find the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'memberships');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Mobile Apps', 'mobile-apps', 290, TRUE,
       'Productivity, education, photo editing and fitness apps worth paying for.',
       'Productivity, education, photo editing and fitness apps. Find premium plans and the latest deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'mobile-apps');

INSERT INTO categories (storefront, name, slug, sort_order, active, description, meta_description)
SELECT 'DIGITAL', 'Games & Gaming', 'gaming', 300, TRUE,
       'Game subscriptions, digital games and gaming software.',
       'Game subscriptions, digital games and gaming software. Find the latest gaming deals.'
  FROM (SELECT 1 AS x) one WHERE NOT EXISTS (SELECT 1 FROM categories WHERE storefront = 'DIGITAL' AND slug = 'gaming');
