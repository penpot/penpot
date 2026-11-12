;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.inspect-text-list-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.inspect.attributes.text :as attributes-text]
   [app.main.ui.inspect.styles.panels.text :as styles-text]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(defn- text-shape
  [paragraph-attrs]
  {:id (uuid/next)
   :type :text
   :content {:type "root"
             :children [{:type "paragraph-set"
                         :children [(merge {:type "paragraph"
                                            :children [{:text "Item"
                                                        :font-id "sourcesanspro"
                                                        :font-family "sourcesanspro"
                                                        :font-size "14"}]}
                                           paragraph-attrs)]}]}})

(defn- render-panel
  [panel shapes]
  (rds/renderToStaticMarkup
   (mf/element panel
               #js {:shapes shapes
                    :resolvedTokens {}
                    :colorSpace "hex"
                    :onFontShorthand identity})))

(t/deftest text-panels-show-the-list-style
  (with-redefs [cf/flags (conj cf/flags :inspect-styles)
                hooks/use-portal-container (mock/stub (fn [_] nil))]
    (let [shapes [(text-shape {:list-style-type "disc"})]]
      (t/testing "styles tab"
        (let [markup (render-panel styles-text/text-panel* shapes)]
          (t/is (str/includes? markup "List Style"))
          (t/is (str/includes? markup ">disc<"))))
      (t/testing "computed tab"
        (let [markup (render-panel attributes-text/text-panel* shapes)]
          (t/is (str/includes? markup "List Style"))
          (t/is (str/includes? markup "inspect.attributes.typography.list-style-type.disc")))))))

(t/deftest text-panels-hide-the-list-style-without-a-list
  (with-redefs [cf/flags (conj cf/flags :inspect-styles)
                hooks/use-portal-container (mock/stub (fn [_] nil))]
    (doseq [paragraph-attrs [{} {:list-style-type "none"}]
            panel [styles-text/text-panel* attributes-text/text-panel*]]
      (let [markup (render-panel panel [(text-shape paragraph-attrs)])]
        (t/is (not (str/includes? markup "List Style")))))))
