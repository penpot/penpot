;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.numeric-input
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.keyboard :as kbd]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.object :as obj]))

(mf/defc numeric-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [on-key-down
        (mf/use-callback
          (fn [event]
            (when (and (or (kbd/up-arrow? event) (kbd/down-arrow? event))
                       (kbd/shift? event))
              (let [increment (if (kbd/up-arrow? event) 9 -9) ; this is added to the
                    target (dom/get-target event)             ; default 1 or -1 step
                    min-value (-> (dom/get-attribute target "min")
                                  (d/parse-integer ##-Inf))
                    max-value (-> (dom/get-attribute target "max")
                                  (d/parse-integer ##Inf))
                    new-value (-> target
                                  (dom/get-value)
                                  (d/parse-integer 0)
                                  (+ increment)
                                  (cljs.core/min max-value)
                                  (cljs.core/max min-value))]
                (dom/set-value! target new-value)))))

          props (-> props
                    (obj/set! "className" "input-text")
                    (obj/set! "type" "number")
                    (obj/set! "ref" ref)
                    (obj/set! "onKeyDown" on-key-down))]

    [:> :input props]))

