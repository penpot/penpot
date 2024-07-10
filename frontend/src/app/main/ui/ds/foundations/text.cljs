;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.text
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.typography :refer [typography-list]]
   [rumext.v2 :as mf]))

(defn- valid-typography? [value]
  (contains? typography-list value))

(mf/defc text*
  {::mf/props :obj}
  [{:keys [tag typography children] :rest props}]

  (assert (valid-typography? (dm/str typography))
          (dm/str typography " is an unknown typography"))

  (let [props (mf/spread-props props {:class (stl/css-case :display-typography (= typography "display")
                                                           :title-large-typography (= typography "title-large")
                                                           :title-medium-typography (= typography "title-medium")
                                                           :title-small-typography (= typography "title-small")
                                                           :headline-large-typography (= typography "headline-large")
                                                           :headline-medium-typography (= typography "headline-medium")
                                                           :headline-small-typography (= typography "headline-small")
                                                           :body-large-typography (= typography "body-large")
                                                           :body-medium-typography (= typography "body-medium")
                                                           :body-small-typography (= typography "body-small")
                                                           :code-font-typography (= typography "code-font"))})]
    [:> tag props
     children]))
