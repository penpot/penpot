(ns frontend-tests.ui.dashboard-import-test
  (:require
   [app.main.data.workspace :as workspace]
   [app.main.ui.dashboard.import :as dashboard-import]
   [app.util.i18n :as i18n]
   [cljs.test :as t]))

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
    (with-redefs [i18n/tr (fn [key]
                            (swap! keys conj key)
                            (str "translated:" key))]
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
             @keys)))
