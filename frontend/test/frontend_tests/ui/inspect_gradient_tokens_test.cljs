;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.inspect-gradient-tokens-test
  (:require
   [app.common.types.color :as ctc]
   [app.common.types.fills :as types.fills]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.gradient :as wtg]
   [app.main.ui.inspect.styles.panels.fill :as fill]
   [app.main.ui.inspect.styles.panels.stroke :as stroke]
   [cljs.test :as t :include-macros true]))

(def ^:private fill-has-token? @#'fill/has-token?)
(def ^:private stroke-has-token? @#'stroke/has-color-token?)

;; A gradient token as tokenscript resolves it. Applying it gives a shape
;; the gradient `value->gradient` makes of it.
(defn- resolve-brand-token
  []
  (-> (ts/resolve-tokens {"gradient.brand"
                          (ctob/make-token {:name "gradient.brand"
                                            :type :gradient
                                            :value {:kind "linear"
                                                    :angle "90"
                                                    :stops [{:color "#ff0000" :position "0"}
                                                            {:color "#0000ff" :position "100%"}]}})})
      (get "gradient.brand")))

(t/deftest inspect-shows-the-gradient-token-of-a-fill
  (let [resolved-token   (resolve-brand-token)
        applied-gradient (wtg/value->gradient (:resolved-value resolved-token))
        color            (types.fills/fill->color {:fill-color-gradient applied-gradient
                                                   :fill-opacity 1})]
    (t/testing "the first fill with the gradient of the token shows it"
      (t/is (fill-has-token? resolved-token color 0)))

    (t/testing "another fill or another gradient shows no token"
      (t/is (not (fill-has-token? resolved-token color 1)))
      (t/is (not (fill-has-token? resolved-token
                                  (types.fills/fill->color
                                   {:fill-color-gradient (assoc applied-gradient :start-x 0.2)
                                    :fill-opacity 1})
                                  0))))))

(t/deftest inspect-shows-the-gradient-token-of-a-stroke
  (let [resolved-token   (resolve-brand-token)
        applied-gradient (wtg/value->gradient (:resolved-value resolved-token))
        color            (ctc/stroke->color {:stroke-color-gradient applied-gradient
                                             :stroke-opacity 1})]
    (t/is (stroke-has-token? resolved-token color 0))
    (t/is (not (stroke-has-token? resolved-token color 1)))))

(t/deftest inspect-keeps-showing-color-tokens
  (let [token {:name "color.red" :resolved-value "#ff0000"}]
    (t/is (fill-has-token? token (types.fills/fill->color {:fill-color "#ff0000" :fill-opacity 1}) 0))
    (t/is (not (fill-has-token? token (types.fills/fill->color {:fill-color "#00ff00" :fill-opacity 1}) 0)))))
