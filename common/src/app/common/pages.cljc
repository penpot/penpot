;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [app.common.data :as d]
   [app.common.pages.changes :as changes]
   [app.common.pages.common :as common]
   [app.common.pages.indices :as indices]
   [app.common.pages.init :as init]))

;; Common
(d/export common/root)
(d/export common/file-version)
(d/export common/default-color)
(d/export common/component-sync-attrs)

;; Indices
(d/export indices/calculate-z-index)
(d/export indices/update-z-index)
(d/export indices/generate-child-all-parents-index)
(d/export indices/generate-child-parent-index)
(d/export indices/create-clip-index)

;; Process changes
(d/export changes/process-changes)

;; Initialization
(d/export init/default-frame-attrs)
(d/export init/default-shape-attrs)
(d/export init/make-file-data)
(d/export init/make-minimal-shape)
(d/export init/make-minimal-group)
(d/export init/empty-file-data)
