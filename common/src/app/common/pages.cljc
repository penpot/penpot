;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.changes :as changes]
   [app.common.pages.common :as common]
   [app.common.pages.indices :as indices]
   [app.common.pages.init :as init]))

;; Common
(dm/export common/root)
(dm/export common/file-version)
(dm/export common/default-color)
(dm/export common/component-sync-attrs)

;; Indices
(dm/export indices/calculate-z-index)
(dm/export indices/update-z-index)
(dm/export indices/generate-child-all-parents-index)
(dm/export indices/generate-child-parent-index)
(dm/export indices/create-clip-index)

;; Process changes
(dm/export changes/process-changes)

;; Initialization
(dm/export init/default-frame-attrs)
(dm/export init/default-shape-attrs)
(dm/export init/make-file-data)
(dm/export init/make-minimal-shape)
(dm/export init/make-minimal-group)
(dm/export init/empty-file-data)
