;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.error-boundary
  "React error boundary components"
  (:require
   ["react-error-boundary" :as reb]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [goog.functions :as gfn]
   [rumext.v2 :as mf]))

(mf/defc error-boundary*
  {::mf/props :obj}
  [{:keys [fallback children]}]
  (let [fallback-wrapper
        (mf/with-memo [fallback]
          (mf/fnc fallback-wrapper*
            {::mf/props :obj}
            [{:keys [error reset-error-boundary]}]
            (let [route (mf/deref refs/route)
                  data  (errors/exception->error-data error)]
              [:> fallback {:data data
                            :route route
                            :on-reset reset-error-boundary}])))

        on-error
        (mf/with-memo []
          ;; NOTE: The debounce is necessary just for simplicity,
          ;; becuase for some reasons the error is reported twice in a
          ;; very small amount of time, so we debounce for 100ms for
          ;; avoid duplicate and redundant reports
          (gfn/debounce (fn [error info]
                          (js/console.log "Cause stack: \n" (.-stack error))
                          (js/console.error
                           "Component trace: \n"
                           (unchecked-get info "componentStack")
                           "\n"
                           error))
                        100))]

    [:> reb/ErrorBoundary
     {:FallbackComponent fallback-wrapper
      :onError on-error}
     children]))
