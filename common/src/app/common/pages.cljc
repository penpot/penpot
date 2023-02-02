;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.changes :as changes]
   [app.common.pages.common :as common]
   [app.common.pages.focus :as focus]
   [app.common.pages.indices :as indices]
   [app.common.types.file :as ctf]))

;; Common
(dm/export common/root)
(dm/export common/file-version)
(dm/export common/default-color)
(dm/export common/component-sync-attrs)
(dm/export common/retrieve-used-names)
(dm/export common/generate-unique-name)

;; Focus
(dm/export focus/focus-objects)
(dm/export focus/filter-not-focus)
(dm/export focus/is-in-focus?)

;; Indices
#_(dm/export indices/calculate-z-index)
#_(dm/export indices/update-z-index)
(dm/export indices/generate-child-all-parents-index)
(dm/export indices/generate-child-parent-index)
(dm/export indices/create-clip-index)

;; Process changes
(dm/export changes/process-changes)

;; Initialization
(dm/export ctf/make-file-data)
(dm/export ctf/empty-file-data)
