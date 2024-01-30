;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.rxops
  (:require
   [beicon.v2.core :as rx]))

(defn throttle-fn
  [delay f]
  (let [state
        #js {:lastExecTime 0
             :timeoutId nil
             :context nil
             :args nil}

        execute-fn
        (fn []
          (let [context (.-context ^js state)
                args    (.-args ^js state)]
            (.apply f context args)
            (set! (.-lastExecTime state) (js/Date.now))
            (set! (.-timeoutId state) nil)))

        wrapped-fn
        (fn []
          (let [ctime (js/Date.now)
                ltime (.-lastExecTime ^js state)
                args  (js-arguments)]

            (this-as this
                     (set! (.-context state) this)
                     (set! (.-args state) args))

            (let [timeout-id (.-timeoutId state)]
              (if (>= (- ctime ltime) delay)
                (do
                  (when ^boolean timeout-id
                    (js/clearTimeout timeout-id)
                    (set! (.-timeoutId state) nil))
                  (execute-fn))

                (when-not ^boolean timeout-id
                  (set! (.-timeoutId state)
                        (js/setTimeout execute-fn (- delay ctime ltime))))))))]

    (specify! wrapped-fn
      rx/IDisposable
      (-dispose [_]
        (js/clearTimeout (.-timeoutId state))
        (set! (.-lastExecTime state) 0)
        (set! (.-timeoutId state) nil)))))


(defn throttle
  "High performance rxjs throttle operation. It does not saturates the
  macro-task queue of the js runtime on long burst of mouse
  movements."
  [delay]
  (fn [source]
    (rx/create
     (fn [subs]
       (let [next-fn  (throttle-fn delay (partial rx/push! subs))
             error-fn (fn [cause]
                        (rx/dispose! next-fn)
                        (rx/error! subs cause))
             end-fn   (fn []
                        (rx/dispose! next-fn)
                        (rx/end! subs))]
         (rx/sub! source next-fn error-fn end-fn))))))
