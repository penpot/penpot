;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.typography.text
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.typography :as t]
   [rumext.v2 :as mf]))

(defn- valid-typography? [value]
  (contains? t/typography-list value))

(def ^:private schema:text
  [:map
   [:as {:optional true} :string]
   [:class {:optional true} :string]
   [:typography [:and :string [:fn #(valid-typography? (dm/str %))]]]])

(mf/defc text*
  {::mf/schema schema:text}
  [{:keys [as typography children class] :rest props}]

  (let [as (if (or (empty? as) (nil? as)) "p" as)
        class (dm/str (or class "") " " (stl/css-case :display-typography (= typography t/display)
                                                      :title-large-typography (= typography t/title-large)
                                                      :title-medium-typography (= typography t/title-medium)
                                                      :title-small-typography (= typography t/title-small)
                                                      :headline-large-typography (= typography t/headline-large)
                                                      :headline-medium-typography (= typography t/headline-medium)
                                                      :headline-small-typography (= typography t/headline-small)
                                                      :body-large-typography (= typography t/body-large)
                                                      :body-medium-typography (= typography t/body-medium)
                                                      :body-small-typography (= typography t/body-small)
                                                      :code-font-typography (= typography t/code-font)))
        props (mf/spread-props props {:class class})]
    [:> as props
     children]))
