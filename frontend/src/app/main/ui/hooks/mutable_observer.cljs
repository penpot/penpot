;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.hooks.mutable-observer
  (:require
   [app.common.logging :as log]
   [rumext.alpha :as mf]))

(log/set-level! :warn)

(defn use-mutable-observer
  [on-change]

  (let [prev-obs-ref (mf/use-ref nil)
        node-ref (mf/use-ref nil)

        on-mutation
        (mf/use-callback
         (mf/deps on-change)
         (fn [mutation]
           (log/debug :action "mutation" :js/mutation mutation)
           (on-change (mf/ref-val node-ref))))
        
        set-node
        (mf/use-callback
         (mf/deps on-mutation)
         (fn [^js node]
           (when (and (some? node) (not= (mf/ref-val node-ref) node))
             (mf/set-ref-val! node-ref node)

             (when-let [^js prev-obs (mf/ref-val prev-obs-ref)]
               (.disconnect prev-obs)
               (mf/set-ref-val! prev-obs-ref nil))

             (when (some? node)
               (let [options #js {:attributes true
                                  :childList true
                                  :subtree true
                                  :characterData true}
                     mutation-obs (js/MutationObserver. on-mutation)]
                 (mf/set-ref-val! prev-obs-ref mutation-obs)
                 (.observe mutation-obs node options))))))]

    (mf/use-effect
     (fn []
       (fn []
         (when-let [^js prev-obs (mf/ref-val prev-obs-ref)]
           (.disconnect prev-obs)
           (mf/set-ref-val! prev-obs-ref nil)))))

    set-node))
