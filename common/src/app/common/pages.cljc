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
   [app.common.pages.indices :as indices]))

;; Indices
(dm/export indices/generate-child-all-parents-index)
(dm/export indices/create-clip-index)

;; Process changes
(dm/export changes/process-changes)
