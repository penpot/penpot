;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.typography.heading
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.typography :as t]
   [rumext.v2 :as mf]))

(defn- valid-level? [value]
  (let [number-set #{"1" "2" "3" "4" "5" "6"}]
    (contains? number-set (dm/str value))))

(defn- valid-typography? [value]
  (contains? t/typography-list value))

(mf/defc heading*
  {::mf/props :obj}
  [{:keys [level typography class children] :rest props}]
  (assert (or (valid-level? level)
              (nil? level))
          (dm/str "Invalid level: " level ". Valid numbers are 1 to 6."))
  (assert (valid-typography? (dm/str typography))
          (dm/str typography " is an unknown typography"))

  (let [level (or level "1")
        tag   (dm/str "h" level)
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
    [:> tag props
     children]))
