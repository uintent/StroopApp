---
layout: default
title: Home
---

# Project Directory

Welcome to the project! Below you'll find all available files and directories.

## 📁 Project Structure

{% assign sorted_pages = site.pages | sort: 'path' %}
{% assign sorted_static_files = site.static_files | sort: 'path' %}

### Pages
<ul>
{% for page in sorted_pages %}
  {% unless page.path contains 'vendor' or page.path contains 'node_modules' %}
  <li>
    <a href="{{ page.url | relative_url }}">
      {% if page.path contains '.md' %}📝{% elsif page.path contains '.html' %}📄{% else %}📋{% endif %}
      {{ page.path }}
    </a>
  </li>
  {% endunless %}
{% endfor %}
</ul>

### Static Files
<ul>
{% for file in sorted_static_files %}
  {% unless file.path contains 'vendor' or file.path contains 'node_modules' %}
  <li>
    <a href="{{ file.path | relative_url }}">
      {% if file.extname == '.jpg' or file.extname == '.png' or file.extname == '.gif' %}🖼️{% elsif file.extname == '.pdf' %}📑{% elsif file.extname == '.zip' %}📦{% else %}📎{% endif %}
      {{ file.path }}
    </a>
  </li>
  {% endunless %}
{% endfor %}
</ul>

## Directory Tree
{% include directory_tree.html %}

## 🔗 Quick Links

- [View on GitHub](https://github.com/uintent/combatTracker2)
- [Download Repository](https://github.com/uintent/combatTracker2/archive/refs/heads/main.zip)

---

*Last updated: {{ site.time | date: "%B %d, %Y" }}*
