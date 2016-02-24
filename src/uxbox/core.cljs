(ns uxbox.core
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.router :as rt]
            [uxbox.rstore :as rs]
            [uxbox.ui :as ui]
            [uxbox.data.load :as dl]))

(enable-console-print!)

(def ^:const ^:private +persistent-keys+
  [:auth
   :pages-by-id
   :shapes-by-id
   :colors-by-id
   :projects-by-id])

(defn- main
  []
  (let [lens (l/select-keys +persistent-keys+)
        stream (->> (l/focus-atom lens st/state)
                    (rx/from-atom)
                    (rx/dedupe)
                    (rx/debounce 1000)
                    (rx/tap #(println "[save]")))]
    (rx/on-value stream #(dl/persist-state %))))

(define-once :setup
  (println "bootstrap")
  (st/init)
  (rt/init)
  (ui/init)

  (rs/emit! (dl/load-data))

  ;; During development, you can comment the
  ;; following call for disable temprary the
  ;; local persistence.
  (main))
