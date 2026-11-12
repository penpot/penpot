(ns frontend-tests.ui.dashboard-import-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react-dom" :refer [flushSync]]
   ["react-dom/client" :refer [createRoot]]
   [app.common.uuid :as uuid]
   [app.main.data.imports :as imp]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as workspace]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.dashboard.import :as dashboard-import]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(t/deftest pending-token-source-uses-token-aware-resolution
  (t/is (= {:command :resolve-import-token-source
            :params {:file-id "imported-file"
                     :library-id "selected-library"
                     :tokens-status-names {}}}
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "original-library" :tokens-source? true
             :tokens-status-names {}}
            "selected-library")))

  (t/is (= {:command :resolve-import-token-source
            :params {:file-id "imported-file"
                     :library-id "selected-library"}}
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "original-library" :tokens-source? true}
            "selected-library")))

  (t/is (nil? (dashboard-import/pending-library-resolution-request
               "imported-file"
               {:id "original-library" :tokens-source? true
                :tokens-status-names {}}
               nil))))

(t/deftest ordinary-pending-library-uses-generic-linking
  (t/is (= {:command :link-file-to-library
            :params {:file-id "imported-file"
                     :library-id "selected-library"}}
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "ordinary-library"}
            "selected-library")))

  (t/is (nil? (dashboard-import/pending-library-resolution-request
               "imported-file"
               {:id "ordinary-library"}
               nil))))

(t/deftest token-source-fallback-outcome-uses-rpc-response-map
  (t/is (= :tokens-source-fallback-local
           (workspace/token-source-fallback-notification-outcome
            {:tokens-source-fallback-notification :tokens-source-fallback-local})))
  (t/is (= :tokens-source-deactivated
           (workspace/token-source-fallback-notification-outcome
            {:tokens-source-fallback-notification :tokens-source-deactivated})))
  (t/is (nil? (workspace/token-source-fallback-notification-outcome
               {:tokens-source-fallback-notification nil})))
  (t/is (nil? (workspace/token-source-fallback-notification-outcome
               {:tokens-source-fallback-notification :tokens-source-restored})))
  (t/is (nil? (workspace/token-source-fallback-notification-outcome {})))
  (t/is (nil? (workspace/token-source-fallback-notification-outcome
               :tokens-source-fallback-local))))

(t/deftest token-source-fallback-messages-are-translated
  (let [keys (atom [])]
    (with-redefs [i18n/tr (mock/stub (fn [key]
                                       (swap! keys conj key)
                                       (str "translated:" key)))]
      (t/is (= "translated:dashboard.import.tokens-source-fallback-local"
               (workspace/token-source-fallback-notification-message
                :tokens-source-fallback-local)))
      (t/is (= "translated:dashboard.import.tokens-source-deactivated"
               (workspace/token-source-fallback-notification-message
                :tokens-source-deactivated)))
      (t/is (nil? (workspace/token-source-fallback-notification-message
                   :tokens-source-restored)))
      (t/is (nil? (workspace/token-source-fallback-notification-message
                   {:tokens-source-fallback-notification :tokens-source-fallback-local}))))
    (t/is (= ["dashboard.import.tokens-source-fallback-local"
              "dashboard.import.tokens-source-deactivated"]
             @keys))))

(t/deftest ^:async failed-library-links-stay-open-and-retry-only-unfinished-links
  (doseq [token-source? [false true]]
    (let [names {"activeThemes" ["Dark"] "activeSets" ["shared"]}
          resolution {:unrelated-key {:id "file" :pending [{:id "ordinary"}
                                                           {:id "source" :tokens-source? token-source?
                                                            :tokens-status-names names}]}}
          selection {"file" {"ordinary" "assets" "source" "tokens"}}
          resolved* (atom #{})
          linking?* (atom false)
          calls (atom [])
          events (atom [])
          finished (atom 0)
          response* (atom (rx/subject))
          confirm! (fn [] (@#'dashboard-import/confirm-library-links!
                           resolution selection resolved* linking?* #(swap! finished inc)))]
      (await
       (mock/with-mocks*
         {rp/cmd! (mock/stub (fn [command params]
                               (swap! calls conj [command params])
                               (if (= "assets" (:library-id params))
                                 (->> (rx/of nil) (rx/observe-on :async))
                                 (->> @response* (rx/take 1) (rx/observe-on :async)))))
          st/emit! (mock/stub (fn [event] (swap! events conj event)))
          ntf/error (mock/stub (fn [message] [:error message]))
          modal/hide (mock/stub (fn [] :hide))}
         (confirm!)
         (await (a/wait-for #(= 2 (count @calls)) "second link is pending"))
         (confirm!)
         (t/is (= 2 (count @calls)) "in-flight confirmation cannot submit twice")
         (rx/error! @response* (ex-info "link rejected" {:hint "Permission denied"}))
         (await (a/wait-for #(false? @linking?*) "failed confirmation permits retry"))
         (t/is (= [[:error "Permission denied"]] @events))
         (t/is (zero? @finished))
         (t/is (= 1 (count @resolved*)))
         (t/is (= (if token-source? :resolve-import-token-source :link-file-to-library)
                  (first (second @calls))))
         (when token-source?
           (t/is (= names (get-in @calls [1 1 :tokens-status-names]))))
         (reset! response* (rx/subject))
         (confirm!)
         (await (a/wait-for #(= 3 (count @calls)) "only failed link is retried"))
         (rx/push! @response* {})
         (await (a/wait-for #(= 1 @finished) "all links complete"))
         (t/is (= [[:error "Permission denied"] :hide] @events))
         (t/is (= ["assets" "tokens" "tokens"] (mapv #(get-in % [1 :library-id]) @calls)))
         (t/is (= 2 (count @resolved*))))))))

(t/deftest ^:async skipping-all-pending-links-completes-without-an-rpc
  (let [events (atom [])
        finished (atom false)]
    (await (mock/with-mocks*
             {rp/cmd! (mock/stub (fn [& _] (t/is false "skipped links must not call RPC")))
              st/emit! (mock/stub (fn [event] (swap! events conj event)))
              modal/hide (mock/stub (fn [] :hide))}
             (@#'dashboard-import/confirm-library-links!
              {:different-key {:id "file" :pending [{:id "source" :tokens-source? true}]}}
              {} (atom #{}) (atom false) #(reset! finished true))
             (await (a/wait-for #(true? @finished) "skip completes"))
             (t/is (= [:hide] @events))))))

(t/deftest selected-token-source-keeps-its-resolution-metadata
  (let [entry {:id "original" :tokens-source? true
               :tokens-status-names {"activeThemes" ["/Dark"] "activeSets" ["shared"]}
               :tokens-source-fallback :tokens-source-fallback-local}]
    (t/is (= [(merge {:file-id "file" :library-id "replacement"}
                     (dissoc entry :id))]
             (@#'dashboard-import/pending-library-links
              {:unrelated-key {:id "file" :pending [entry]}}
              {"file" {"original" "replacement"}})))))

(t/deftest ^:async mounted-import-dialog-confirms-and-retries-library-links
  (doseq [token-source? [false true]]
    (let [window (.-window (JSDOM. "<div id='root'></div>"))
          previous-window (.-window js/globalThis)
          previous-document (.-document js/globalThis)
          file-id (uuid/next)
          names {"activeThemes" ["/Dark"] "activeSets" ["shared"]}
          resolution {file-id {:id file-id :name "Imported" :done []
                               :pending [{:id "ordinary" :name "Assets"}
                                         {:id "source" :name "Tokens"
                                          :tokens-source? token-source?
                                          :tokens-status-names names}]}}
          calls (atom [])
          events (atom [])
          finished (atom 0)
          response* (atom (rx/subject))
          container (.getElementById (.-document window) "root")
          button (fn [label]
                   (some #(when (= label (.-textContent %)) %)
                         (array-seq (.querySelectorAll container "button"))))]
      (set! (.-window js/globalThis) window)
      (set! (.-document js/globalThis) (.-document window))
      (let [root (createRoot container)]
        (try
          (await
           (mock/with-mocks*
             {globals/window window
              globals/document (.-document window)
              imp/analyze (mock/stub (fn [_]
                                       (->> (rx/of {:file-id file-id :name "Imported" :status :success})
                                            (rx/observe-on :async))))
              imp/import-files (mock/stub (fn [_]
                                            (->> (rx/of {:libraries-resolution resolution}
                                                        {:file-id file-id :status :finish})
                                                 (rx/observe-on :async))))
              dashboard-import/import-library-resolution-stage*
              (fn [^js props]
                (mf/element "button"
                            #js {:onClick (fn []
                                            ((.-onSelect props) file-id "ordinary" "assets")
                                            ((.-onSelect props) file-id "source" "tokens")
                                            ((.-onWizardNext props)))
                                 :children "Select libraries"}))
              rp/cmd! (mock/stub (fn [command params]
                                   (swap! calls conj [command params])
                                   (if (= "assets" (:library-id params))
                                     (->> (rx/of {}) (rx/observe-on :async))
                                     (->> @response* (rx/take 1) (rx/observe-on :async)))))
              i18n/tr (mock/stub identity)
              st/emit! (mock/stub (fn [event] (swap! events conj event)))
              ntf/error (mock/stub (fn [message] [:error message]))
              modal/hide (mock/stub (fn [] :hide))}
             (try
               (flushSync #(.render root (mf/element dashboard-import/import-dialog
                                                     #js {:entries [{:file-id file-id :name "Imported"}]
                                                          :on-finish-import (fn [] (swap! finished inc))})))
               (await (a/wait-for #(button "labels.continue") "analysis completes" {:max-ticks 1000}))
               (.click (button "labels.continue"))
               (await (a/wait-for #(button "Select libraries") "library selection appears"))
               (.click (button "Select libraries"))
               (await (a/wait-for #(button "dashboard.import.confirm-library-links") "summary appears"))
               (.click (button "dashboard.import.confirm-library-links"))
               (await (a/wait-for #(= 2 (count @calls)) "mounted dialog submits both links"))
               (rx/error! @response* (ex-info "link rejected" {:hint "Permission denied"}))
               (await (a/wait-for #(some #{[:error "Permission denied"]} @events) "error is displayed"))
               (t/is (zero? @finished))
               (t/is (not-any? #{:hide} @events))
               (await (a/wait-for #(not (.-disabled (button "dashboard.import.confirm-library-links")))
                                  "confirmation permits retry"))
               (reset! response* (rx/subject))
               (.click (button "dashboard.import.confirm-library-links"))
               (await (a/wait-for #(= 3 (count @calls)) "only unfinished link is retried after rerender"))
               (rx/push! @response* {})
               (await (a/wait-for #(= 1 @finished) "dialog completes"))
               (t/is (some #{:hide} @events))
               (t/is (= ["assets" "tokens" "tokens"] (mapv #(get-in % [1 :library-id]) @calls)))
               (t/is (= [:link-file-to-library
                         (if token-source? :resolve-import-token-source :link-file-to-library)
                         (if token-source? :resolve-import-token-source :link-file-to-library)]
                        (mapv first @calls)))
               (when token-source?
                 (t/is (= names (get-in @calls [1 1 :tokens-status-names]))))
               (finally
                 (flushSync #(.unmount root))))))
          (finally
            (set! (.-window js/globalThis) previous-window)
            (set! (.-document js/globalThis) previous-document)
            (.close window)))))))
