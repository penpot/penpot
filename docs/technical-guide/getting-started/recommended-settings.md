---
title: 1.1 Recommended storage
desc: Learn recommended self-hosting settings, Docker & Kubernetes installs, configuration, and troubleshooting tips in Penpot's technical guide.
---

# Recommended storage

Disk requirements depend on your usage, with the primary factors being database storage and user-uploaded files.

As a rule of thumb, start with a **minimum** database size of **50GB** to **100GB** with elastic sizing capability — this configuration should adequately support up to 10 editors. For environments with **more than 10 users**, we recommend adding approximately **5GB** of capacity per additional editor.

Keep in mind that database size doesn't grow strictly proportionally with user count, as it depends heavily on how Penpot is used and the complexity of files created. Most organizations begin with this baseline and elastic sizing approach, then monitor usage patterns monthly until resource requirements stabilize.
