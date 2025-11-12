;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.utilities.date
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(defn valid-date?
  [date]
  (or (ct/inst? date) (number? date)))

(def ^:private schema:date
  [:map
   [:class {:optional true} :string]
   [:as {:optional true} :string]
   [:date [:fn valid-date?]]
   [:selected {:optional true} :boolean]
   [:typography {:optional true} :string]])

(mf/defc date*
  {::mf/schema schema:date}
  [{:keys [class date selected typography] :rest props}]
  (let [class (d/append-class class (stl/css-case :date true :is-selected selected))
        date  (cond-> date (not (ct/inst? date)) ct/inst)
        typography (or typography t/body-medium)]
    [:> text* {:as "time"
               :typography typography
               :class class
               :date-time (ct/format-inst date :iso)}
     (dm/str
      (ct/format-inst date :localized-date)
      " . "
      (ct/format-inst date :localized-time)
      "h")]))
