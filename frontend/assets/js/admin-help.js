/* Admin: Website instructions. Every feature of the store and admin panel explained in simple language, step by step. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { SF, SF_NAME, view, head } = K;
  const SHOP = SF === 'shop';
  const SITE = '/' + SF;

  // Each section: title, what (one-line explanation), where (menu path), steps, tips, parts (numbered sub-features).
  const SECTIONS = [
    {
      title: 'First-time setup (do this once)',
      what: 'A short checklist to get ' + SF_NAME + ' ready before you add products.',
      where: 'Several menus — follow the steps in order',
      steps: [
        'Sign in with the admin email and password you were given. You will be asked to choose a new password.',
        'Open My account → Two-factor sign-in and turn it on with an authenticator app (Google Authenticator, Microsoft Authenticator). This protects your account even if someone learns your password.',
        'Open Settings → Store & invoice details. Fill in store name, support email, legal name, GSTIN, address and invoice prefix.',
        'Open Settings → Email (SMTP). Enter your email provider details and press "Send test". Until this works, no emails go out (they show as "Skipped" in Email log).',
        SHOP ? 'Open Settings → Shipping methods and check the delivery charges.' : 'Digital products need no shipping setup.',
        'Open Pages. Edit each legal page (privacy, terms, refunds, affiliate disclosure, about, contact) and replace every [bracketed] value.',
        'Open SEO → Site settings. Write the home page title and description and paste your Google / Bing verification codes.',
        'Add your first products (see "Products"), then create your first Web Story and buying guide.',
        'Open Staff to add other people with limited roles, so nobody shares your login.'
      ],
      tips: ['You can come back to this page any time from the menu: Website instructions.']
    },
    {
      title: 'How the menu is organised',
      what: 'The left menu is split by the way you sell, so each kind of product has its own section.',
      where: 'Left menu',
      steps: SHOP ? [
        '"1 · Affiliate products": products bought on a partner site (Amazon, Flipkart…). Contains Affiliate products, Import products, Links & earnings, Coupons & offers and Research.',
        '"2 · Dropshipping products": products your supplier ships. Contains Dropship products and Dropship orders.',
        '"3 · E-commerce products": products you stock and ship yourself. Contains Own-stock products, Inventory, Own-stock orders, Digital access and Coupons.',
        '"Catalog & marketing": tools shared by every section — All products, Categories, Reviews, SEO, Content studio, Buying guides, Web Stories and Pages.',
        '"Store & team": Settings, Email log, Staff, Audit log, System health, Security, My account and these instructions.'
      ] : [
        '"1 · Affiliate products": products bought on a partner site (Udemy, Amazon…). Contains Affiliate products, Import products, Links & earnings, Coupons & offers and Research.',
        '"2 · My own products": courses, e-books, templates or software you made and upload to sell. Contains My own products, My product orders, Digital access and Coupons.',
        '"Catalog & marketing": tools shared by every section — All products, Categories, Inventory, Reviews, SEO, Content studio, Buying guides, Web Stories and Pages.',
        '"Store & team": Settings, Email log, Staff, Audit log, System health, Security, My account and these instructions.'
      ],
      tips: ['A product page inside a section only shows that kind of product, and "New product" there creates that kind automatically.',
        '"All products" and "All orders" still show everything in one list.',
        'An order containing items of several kinds appears in each matching orders page.']
    },
    {
      title: 'Dashboard',
      what: 'The first screen after sign-in: a quick summary of today’s activity and recent orders.',
      where: 'Menu → Dashboard',
      steps: [
        'Open Dashboard to see the main numbers at a glance.',
        'Click any recent order to open it.',
        'For deeper numbers use "Sales & demand" (next section).'
      ]
    },
    {
      title: 'Sales & demand',
      what: 'Shows what sells, what shoppers want, and what they search for but you don’t have.',
      where: 'Menu → Sales & demand',
      steps: [
        'Choose a period at the top: Today, This week, This month, Last 6 months or This year. Every number is compared with the period before.',
        'Read "Most in demand": products ranked by views, saves, price alerts, clicks to the store and sales.',
        'Check "Best sellers" and "Rising fast" to decide which products to promote.',
        'Read "Searched, but you don’t have it": these are product ideas shoppers already want. Add them.',
        'Use the "Market research (India)" links to open Google Trends, Amazon and Flipkart best-seller lists.'
      ],
      tips: ['Views and searches are counted without personal data. Bots are ignored.']
    },
    {
      title: 'CRM (customers, tasks and email campaigns)',
      what: 'Turns sign-ups, saved carts, wishlists, clicks and sales into a simple to-do list for selling.',
      where: 'Menu → CRM',
      parts: [
        { title: 'Overview', steps: [
          'Open CRM → Overview to see customers, subscribers, hot leads and abandoned carts.',
          'Start with "Contact these first": customers with the highest buying interest (score 0-100).',
          'Under Segments, click View to see the people, or Email to write to them.'
        ] },
        { title: 'Customers', steps: [
          'Open CRM → Customers and search or sort (buying intent, total spend, newest).',
          'Click a customer to see everything they did: orders, cart, wishlist, clicks, reviews and emails.',
          'Add tags (for example "VIP"), private notes and follow-up tasks with a date.',
          'Use the email, call or WhatsApp buttons to contact them.'
        ] },
        { title: 'Tasks', steps: [
          'Open CRM → Tasks to see follow-ups that are due.',
          'Tick a task when it is done.'
        ] },
        { title: 'Campaigns (email newsletters)', steps: [
          'Open CRM → Campaigns → New campaign.',
          'Choose who gets it (a segment, for example "Email subscribers").',
          'Write a subject and message, or start from a template. {name}, {store}, {link} and {coupon} are filled in for each person.',
          'Press "Send test" to email yourself first.',
          'Press Send. Results (clicks, sales, coupon use) are counted for 7 days.'
        ] }
      ],
      tips: ['Marketing emails only go to customers who said yes to offers. Each email has a one-click unsubscribe link.',
        'Only master admins can send campaigns.']
    },
    {
      title: 'Automations (free, run by themselves)',
      what: 'Emails and checks that work in the background so you don’t have to remember them.',
      where: 'Menu → CRM → Automations',
      steps: [
        'Make sure email (SMTP) works first (Settings → Email).',
        'Turn on the emails you want: welcome email, saved-cart reminder, win-back, weekly deals digest, review request.',
        'Turn on the daily summary to get one email each morning with clicks, sales, broken links and tasks.',
        'The broken-link check is already on: every night each partner link is checked and dead links are paused. Press "Check links now" to run it immediately.',
        'Optional Telegram channel: create a bot with @BotFather, add it as admin of your channel, paste the bot token and channel name, then press "Send test message". New deals and price drops are posted automatically.'
      ],
      tips: ['Everything here is free. It uses your own email account and a free Telegram bot.']
    },
    {
      title: 'Products',
      what: 'Add, edit and publish the products shown on your store.',
      where: SHOP ? 'Menu → Affiliate products / Dropship products / Own-stock products (or All products)'
        : 'Menu → Affiliate products / My own products (or All products)',
      steps: [
        'Open the section for the kind of product you want to add and click "New … product". The selling method is filled in for you' + (SHOP ? ' (in "All products" you choose it yourself).' : '.'),
        'Fill in the title and press "Create draft". Then add the summary (one clear sentence) and description.',
        'Fill in Highlights, Pros, Cons, Verdict, Our rating and Specifications. These make product pages, blog posts and posters much better.',
        'Under Images, upload photos (JPEG or PNG work everywhere). The first image is the main one; use "Make main" to change it.',
        'Optional: under Videos, add up to 3 short MP4 clips (15-60 seconds work best).',
        'For affiliate products: under Partner stores, paste your affiliate link and the price. The first link to a new website asks you to confirm it once.',
        'For own-stock products: set price, compare-at price (MRP), SKU and stock.',
        'Press Publish. The product is now live on ' + SITE + '.'
      ],
      tips: ['"Deal of the Day" on the product page puts it on the home page with a countdown.',
        'A product cannot be published until it has the details it needs; the page tells you what is missing.']
    },
    {
      title: 'Import products (spreadsheet)',
      what: 'Add many affiliate products at once from Excel or Google Sheets.',
      where: 'Menu → Import products',
      steps: [
        'Click "Download template" and open it in Excel or Google Sheets.',
        'Fill one product per row. Title and link are required; price, MRP, category, brand, store, summary and highlights are optional.',
        'Save as "CSV UTF-8".',
        'Upload the file and press "Check file". Nothing is saved yet; every problem is explained per row.',
        'Fix any problems, then import. Products are added as drafts.',
        'Add a photo to each draft and press Publish.'
      ],
      tips: ['Up to 500 products per file. Products with the same title are skipped.']
    },
    {
      title: 'Categories',
      what: 'Groups of products (for example "Earbuds" or "Online courses") used in menus and filters.',
      where: 'Menu → Categories',
      steps: [
        'Type a category name and add it.',
        'Choose the category on each product.',
        'Empty categories are hidden from shoppers automatically.'
      ]
    },
    {
      title: 'Inventory',
      what: 'Stock levels for products you sell from your own stock.',
      where: 'Menu → Inventory',
      steps: [
        'Open Inventory to see how many of each item you have.',
        'Update the number when new stock arrives.',
        'Items that run out show as out of stock on the store.'
      ],
      tips: ['Affiliate and dropship products do not use inventory.']
    },
    {
      title: 'SEO (getting found on Google)',
      what: 'Tools to help your pages rank on Google.',
      where: 'Menu → SEO',
      steps: [
        'SEO → Overview: read the site audit and "Fix these products first".',
        'SEO → Products: open a product with a low score. Each check tells you what to fix. The Google preview updates while you type.',
        'SEO → Site settings: home page title, description and verification codes.',
        'SEO → Redirects: send an old address to a new one. Address changes on products, guides and stories add redirects automatically.',
        'SEO → 404 monitor: pages people tried to open that don’t exist. Add a redirect for each.',
        'SEO → Sitemap: submit https://your-domain/sitemap.xml in Google Search Console.'
      ]
    },
    {
      title: 'Reviews',
      what: 'Customer reviews shown on product pages.',
      where: 'Menu → Reviews',
      steps: [
        'The menu shows how many reviews are waiting.',
        'Approve real reviews and reject spam.',
        'Use "Add a review" for feedback a customer sent by WhatsApp, email or phone. It is labelled with its source.'
      ],
      tips: ['Only add real customer feedback.']
    },
    {
      title: 'Orders',
      what: 'Orders placed on this site (only when checkout is turned on in Settings → Selling channels).',
      where: SHOP ? 'Menu → Dropship orders / Own-stock orders (or All orders)' : 'Menu → My product orders (or All orders)',
      steps: [
        SHOP ? 'Open Dropship orders (send these to your supplier) or Own-stock orders (pack and ship yourself), and filter by status.'
          : 'Open My product orders and filter by status.',
        'Click an order to see items, customer, payment and invoice.',
        SHOP ? 'Add the courier and tracking number and mark it Shipped, then Delivered.' : 'Digital orders are delivered automatically to the customer’s library.',
        'Refund the full order or part of it if needed. Online payments are refunded through the payment provider.',
        'Print or save the GST invoice as PDF.'
      ],
      tips: ['"Needs attention" means a payment arrived after the stock hold expired; check it and refund if needed.']
    },
    {
      title: 'Digital access',
      what: 'Who can download which digital product, and how many times they did.',
      where: 'Menu → Digital access',
      steps: [
        'Search for a customer or product.',
        'See when access was granted and how many downloads happened.'
      ]
    },
    {
      title: 'Coupons (for checkout)',
      what: 'Discount codes customers type at checkout on this site.',
      where: 'Menu → Coupons',
      steps: [
        'Fill in "New coupon": code, percent or fixed amount, optional cap, minimum order, dates and usage limits.',
        'Save. Share the code in campaigns using {coupon}.'
      ]
    },
    {
      title: 'Affiliate (partner links and commission)',
      what: 'Everything about partner stores: links, approved websites, sales and earnings.',
      where: 'Menu → 1 · Affiliate products → Links & earnings',
      steps: [
        'Links & earnings → Links: every partner link with its price. Links marked as old prices need a fresh price check.',
        'Links & earnings → Merchants: the partner websites you allowed (Amazon, Flipkart, …). Turn on "Hide prices" for stores that don’t allow showing prices.',
        'Links & earnings → Conversions: sales reported by partner networks. Import a CSV or give your network the signed postback URL shown there.',
        'Links & earnings → Report: clicks, sales, conversion rate and commission per product and merchant for the last 30 days.'
      ],
      tips: ['Shoppers always go through ' + SITE + '/go/… so every click is counted.']
    },
    {
      title: 'Coupons & offers (partner coupon codes)',
      what: 'Coupon codes and deals from partner stores, shown on the ' + SITE + '/offers page.',
      where: 'Menu → Coupons & offers',
      steps: [
        'Fill in "Add an offer": the partner store, the code (if any), what it gives and the end date.',
        'Save. Shoppers see "Copy code & go", which copies the code and opens the store through your tracked link.',
        'Click "View on site" to check how it looks.'
      ]
    },
    {
      title: 'Research (find what to sell)',
      what: 'Turns a niche idea and your notes into categories, products to look for, and matching affiliate programs.',
      where: 'Menu → Research',
      steps: [
        'Open Research → Run research.',
        'Type a niche (for example "home workout") and any notes.',
        'Read "Do this next", the suggested categories and the Google searches to try.',
        'Research → Affiliate platforms lists programs that fit and how to join.'
      ]
    },
    {
      title: 'Pages (about, contact, legal)',
      what: 'Simple information pages such as privacy policy, terms and about us.',
      where: 'Menu → Pages',
      steps: [
        'Open a page or click "New page".',
        'Write the text. "# " makes a big heading, "- " makes a bullet point, a line with | characters makes a table.',
        'Replace every [bracketed] value in the legal templates before you launch.',
        'Save and publish.'
      ]
    },
    {
      title: 'Web Stories (Google Stories)',
      what: 'Full-screen, tap-through stories that can appear in Google search and Discover.',
      where: 'Menu → Web Stories',
      steps: [
        'Quick way: in "Create from products", pick 3-10 published products, give it a title and press "Create story". Each product becomes a page.',
        'Own way: press "Create blank story", upload a cover photo, then add pages with portrait photos or short videos (up to 60 seconds).',
        'Give each page a short headline and link it to a product.',
        'Press Publish (needs a cover and at least 3 pages).',
        'Stories show at the bottom of store pages, on ' + SITE + '/stories and in the sitemap.'
      ],
      tips: ['Google shows stories only on a real public website (https), after it reads your sitemap.',
        'The Content studio can also make a story for one product in one click.']
    },
    {
      title: 'Buying guides (blog)',
      what: 'Helpful articles such as "Best earbuds under ₹2,000" that bring visitors from Google.',
      where: 'Menu → Buying guides',
      steps: [
        'Click "New guide". Use "Start from an outline" for a ready-made structure.',
        'Write the text. Use "Insert product" to add a product card with price and buy button, or "Insert products row" for 2-4 top picks.',
        'Fill in the search title and description.',
        'Press Preview to check it, then Publish.',
        'Guides show on ' + SITE + '/guides, on the home page and on the products they mention.'
      ],
      tips: ['The Content studio writes guides for you from product details (see the next section).']
    },
    {
      title: 'Content studio (free content marketing — no AI, no paid API)',
      what: 'Makes blog posts, Google Web Stories, newsletters, social media posts, posters, banners, Pinterest pins and WhatsApp messages from your product details. It uses ready-made templates on your own server, so it is completely free.',
      where: 'Menu → Content studio',
      steps: [
        'Make sure the product is published and has a summary, highlights, pros, cons, verdict and a JPEG/PNG photo. Better details = better content.',
        'Open Content studio → One product, search for the product and click it.',
        'Everything below is made instantly. Click "Another version" any time for different wording.'
      ],
      parts: [
        { title: 'Product blog generator', steps: [
          'In "Blog posts" you get 4 articles: Review, Is it worth it?, FAQs and How to choose.',
          'Click "Show the article text" to read it.',
          'Click "Save as draft" (to edit first) or "Publish now".',
          'Click the "Open …" link to edit it in Buying guides.',
          'For comparisons and "Best of" lists: open Content studio → Several products, pick 2-4 products for a comparison or 3+ for a roundup, type a topic and press Generate.'
        ] },
        { title: 'Google Web Stories generator', steps: [
          'In "Google Web Story" you see the pages (intro, highlights, good to know, price and verdict).',
          'Click "Create story draft" or "Publish story". The product photos are used for every page and each page links to the product.',
          'Open it in Web Stories to change any page.'
        ] },
        { title: 'Email newsletter generator', steps: [
          'In "Newsletters" you get a product launch email (and a new-arrivals email when similar products exist).',
          'Click "Save as campaign draft".',
          'Open it from CRM → Campaigns, check it, send a test to yourself, then send it.',
          'For new arrivals, top deals or a collection: use Content studio → Several products.'
        ] },
        { title: 'Social media content generator', steps: [
          'In "Social media & WhatsApp" you get ready captions with hashtags for Instagram, Facebook, LinkedIn, X (Twitter) and Telegram.',
          'Edit the text if you like (the counter shows the limit for each network).',
          'Click "Copy" and paste it in the app, or click "Open share window" to post it directly.',
          'For Instagram: download a poster (below), post it, and paste the caption.'
        ] },
        { title: 'Product poster & banner generator', steps: [
          'In "Posters & banners" choose a style: Light, Dark or Sale.',
          'You get 6 sizes: Instagram post, Instagram portrait, Story/Reel/Status, Pinterest pin, Facebook/LinkedIn/X banner and Website banner.',
          'Click "Download" under the one you need.',
          'For sale or collection banners with several products: Content studio → Several products → pick products → Generate → "Sale & collection banners". Type your own headline (for example "Diwali sale") and press "Update banners".'
        ] },
        { title: 'Pinterest pin generator', steps: [
          'Download the "Pinterest pin 1000×1500" poster.',
          'In the Pinterest box, copy the pin description, or click "Open share window" — it opens Pinterest with your product link, photo and description filled in.',
          'Choose a board and save the pin.'
        ] },
        { title: 'WhatsApp promotional content generator', steps: [
          'In the WhatsApp box you get a short message with emojis, price and product link.',
          'Click "Open share window" to open WhatsApp with the message ready, then choose a chat, group or Status.',
          'Or click "Copy" and paste it into a WhatsApp broadcast list. Use the "Story / Reel / Status" poster for WhatsApp Status.'
        ] },
        { title: 'Automatic content when a product is published', steps: [
          'Open Content studio → Automation.',
          'Blog post: choose Off, "Create a draft" or "Create and publish", and the blog type.',
          'Web Story: choose Off, "Create a draft" or "Create and publish".',
          'Tick "Create a launch newsletter draft" if you want one.',
          'Press Save. From now on, each product published for the first time gets this content automatically.',
          'Open Content studio → History to see everything that was made, automatic or by hand.'
        ] }
      ],
      tips: ['Newsletters are never sent automatically; you always check and send them yourself.',
        'Posting to social networks is done by you (copy or share window); nothing is posted without you.',
        'Content always links to your own product page, so clicks are tracked.']
    },
    {
      title: 'Settings',
      what: 'Store details, how you sell, delivery, email and tracking codes.',
      where: 'Menu → Settings',
      steps: [
        'Store & invoice details: name, support email, GST details, invoice prefix, Google Analytics 4 ID and Meta Pixel ID.',
        'Selling channels: turn on "Sell on this site" to allow own-stock and dropship orders with checkout. Leave it off for affiliate-only.',
        SHOP ? 'Delivery, cash on delivery & reviews: which PIN codes you deliver to, delivery estimate, cash on delivery limits and fee, and review auto-approval.'
          : 'Reviews: choose whether reviews need approval.',
        SHOP ? 'Shipping methods: delivery charges and free-delivery limit.' : 'Digital products need no shipping methods.',
        'Email (SMTP): your email account for sending emails. Press "Send test" after saving.'
      ],
      tips: ['Analytics and Meta Pixel load only after a shopper accepts cookies.']
    },
    {
      title: 'Payments (Razorpay)',
      what: 'Online payments by UPI, cards, net banking and wallets — only needed when checkout is turned on.',
      where: 'Set up once by your developer in the server settings (.env)',
      steps: [
        'Create a Razorpay account and generate API keys (start with Test mode).',
        'Ask your developer to add the keys and a webhook secret, and set the webhook address to https://your-domain/api/payments/webhooks/razorpay.',
        'Place a test order and pay with a Razorpay test card or UPI ID.',
        'Try a refund from the order page in Orders.',
        'When everything works, switch to live keys.'
      ]
    },
    {
      title: 'Email log',
      what: 'Every email the store sent or tried to send.',
      where: 'Menu → Email log',
      steps: [
        'Check whether an email was sent, failed or skipped.',
        'Failed emails are retried automatically; you can also press Retry.'
      ]
    },
    {
      title: 'Staff and roles',
      what: 'Give other people their own login with only the access they need.',
      where: 'Menu → Staff',
      steps: [
        'Add a staff member with their email and a role.',
        'Master admin: everything. Catalog manager: products, guides, stories, studio, affiliate. Order manager: orders, inventory, CRM. Support: view only, plus CRM notes.',
        'Remove access when someone leaves.'
      ],
      tips: ['The ' + SF_NAME + ' admin and the other store’s admin are completely separate.']
    },
    {
      title: 'Audit log, System health and Security',
      what: 'Checks for master admins: who changed what, whether the server is healthy, and how safe the setup is.',
      where: 'Menu → Audit log / System health / Security',
      steps: [
        'Audit log: every change made in the admin, with who and when. Automatic content shows as "automation".',
        'System health: database, email queue, storage and grouped server errors.',
        'Security: a score with "Fix these first". Work through the list top to bottom.'
      ]
    },
    {
      title: 'My account',
      what: 'Your own password and two-factor sign-in.',
      where: 'Menu → My account',
      steps: [
        'Change password: type the old one and the new one.',
        'Two-factor sign-in: scan the QR code with an authenticator app and type the 6-digit code.'
      ]
    },
    {
      title: 'What shoppers see on the website',
      what: 'Features on ' + SITE + ' that work by themselves once products are published.',
      where: 'Open the store: ' + SITE,
      steps: [
        'Search with suggestions while typing, sort and filters (store, discount, price).',
        'Product pages with photos, videos, highlights, pros and cons, rating, specifications, price history and similar deals.',
        'Compare up to 4 products, recently viewed products, wishlist and "save to cart".',
        'Price-drop alerts and newsletter sign-up (both confirmed by email).',
        'Share buttons and a WhatsApp chat button.',
        'Coupons & offers page, a page per partner store, Deal of the Day with countdown.',
        'Web Stories, buying guides, and "Install the app" (works like a phone app).',
        'A cookie banner: shoppers choose whether analytics may load.'
      ]
    },
    {
      title: 'Simple daily and weekly routine',
      what: 'A habit that keeps the store growing with little effort.',
      where: 'Suggested routine',
      steps: [
        'Every day: read the daily summary email, approve reviews, publish 1-2 new products.',
        'Every day: for each new product, open Content studio and share its WhatsApp message and Instagram poster.',
        'Every week: check Sales & demand → "Searched, but you don’t have it" and add those products.',
        'Every week: publish one buying guide (comparison or "Best of") and one Web Story.',
        'Every week: send one newsletter (top deals or new arrivals) from Content studio → Several products.',
        'Every month: check SEO → Products and Security, and update old guides.'
      ]
    },
    {
      title: 'Problems and quick fixes',
      what: 'Common questions.',
      where: 'Help',
      steps: [
        'Emails are not arriving → Settings → Email (SMTP) → "Send test", then check Email log.',
        'A product is missing in Content studio or Web Stories → it must be Published.',
        'Poster has no photo / story cannot be made → upload a JPEG or PNG photo (WebP cannot be used for posters).',
        'A partner link was paused → it returned "not found" twice; replace the link on the product page.',
        'A menu item is missing → your role does not include it; ask a master admin.',
        'Something looks old after an update → press Ctrl+F5 to reload the page.'
      ]
    }
  ];

  function numberedList(items) {
    return el('ol', { class: 'help-steps' }, items.filter(Boolean).map((s) => el('li', { text: s })));
  }

  function sectionCard(s, n) {
    return el('section', { class: 'card help-section', id: 'help-' + n },
      el('h2', { text: n + '. ' + s.title }),
      el('p', { text: s.what }),
      s.where ? el('p', { class: 'muted small' }, el('strong', { text: 'Where: ' }), s.where) : null,
      s.steps ? [el('h3', { text: 'Steps' }), numberedList(s.steps)] : null,
      s.parts ? s.parts.map((p, i) => el('div', { class: 'help-part' },
        el('h3', { text: n + '.' + (i + 1) + ' ' + p.title }), numberedList(p.steps))) : null,
      s.tips ? el('div', { class: 'help-tips' }, el('strong', { text: 'Good to know' }),
        el('ul', null, s.tips.map((t) => el('li', { text: t })))) : null,
      el('p', { class: 'small' }, el('a', { href: '#/help', text: '↑ Back to contents', on: { click: (e) => { e.preventDefault(); window.scrollTo({ top: 0, behavior: 'smooth' }); } } })));
  }

  function helpView() {
    const cards = SECTIONS.map((s, i) => sectionCard(s, i + 1));
    const goTo = (i) => (e) => { e.preventDefault(); cards[i].scrollIntoView({ behavior: 'smooth', block: 'start' }); };
    const toc = el('ol', { class: 'help-toc' }, SECTIONS.map((s, i) => el('li', null,
      el('a', { href: '#/help', text: s.title, on: { click: goTo(i) } }))));
    const search = el('input', { type: 'search', placeholder: 'Search the instructions, e.g. WhatsApp, poster, refund…', 'aria-label': 'Search instructions' });
    const none = el('p', { class: 'muted', text: 'Nothing matches. Try another word.', hidden: true });
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      let shown = 0;
      cards.forEach((c) => {
        const match = !q || c.textContent.toLowerCase().includes(q);
        c.hidden = !match;
        if (match) shown++;
      });
      toc.parentElement.hidden = !!q;
      none.hidden = shown > 0;
    });
    mount(view(), head('Website instructions', el('a', { class: 'btn secondary', href: SITE, target: '_blank', rel: 'noopener', text: 'Open the store' })),
      el('div', { class: 'card' },
        el('p', null, 'How to use every feature of ' + SF_NAME + ' and this admin panel, in simple steps. ',
          'Some menus are only visible to certain roles. The ' + (SHOP ? 'Elaatech (digital)' : 'Elaashop') + ' admin has its own copy of these instructions.'),
        search),
      el('div', { class: 'card' }, el('h2', { text: 'Contents' }), toc),
      none, cards);
  }

  K.register('help', 'Website instructions', null, helpView, 'account');
})();
