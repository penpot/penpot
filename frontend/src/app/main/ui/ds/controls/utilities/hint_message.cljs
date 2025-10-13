;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.utilities.hint-message
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

(def ^:private schema::hint-message
  [:map
   [:message [:or fn? :string]]
   [:id :string]
   [:type {:optional true} [:enum "hint" "error" "warning"]]
   [:class {:optional true} :string]])

(mf/defc hint-message*
  {::mf/schema schema::hint-message}
  [{:keys [id class message type] :rest props}]
  (let [type (d/nilv type :hint)]
    [:> "div" {:class (dm/str class " " (stl/css-case
                                         :hint-message true
                                         :type-hint (= type "hint")
                                         :type-warning (= type "warning")
                                         :type-error (= type "error")))
               :aria-live (when (or (= type "warning") (= type "error")) "polite")}
     (when (some? message)
       [:span {:class (stl/css :hint-message-text)
               :id (str id "-hint")}
        message])]))
