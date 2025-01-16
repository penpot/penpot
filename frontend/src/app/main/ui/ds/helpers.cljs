;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.helpers
  "A collection of helpers for exporting them to be used on storybook code."
  (:require
   [app.common.uuid :refer [random]]
   [rumext.v2 :as mf]))

(def default
  (mf/object
   {:generate-uuid random
    :keyword keyword}))

