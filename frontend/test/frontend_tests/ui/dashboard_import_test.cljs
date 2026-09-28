(ns frontend-tests.ui.dashboard-import-test
  (:require
   [app.main.ui.dashboard.import :as dashboard-import]
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

(t/deftest token-aware-resolution-includes-candidates
  (t/is (= {:command :resolve-import-token-source
             :params {:file-id "imported-file"
                      :library-id "selected-library"
                      :tokens-status-names {}
                      :candidate-ids ["candidate-a" "candidate-b"]}}
            (dashboard-import/pending-library-resolution-request
             "imported-file"
             {:id "original-library" :tokens-source? true
              :tokens-status-names {}
              :candidates [{:id "candidate-a"} {:id "candidate-b"}]}
             "selected-library"))))

(t/deftest skipped-token-source-uses-pending-fallback
  (t/is (= :tokens-source-fallback-local
            (dashboard-import/skipped-token-source-outcome
             {:id "original-library" :tokens-source? true
              :tokens-status-names {}
              :tokens-source-fallback :tokens-source-fallback-local})))
  (t/is (= :tokens-source-deactivated
            (dashboard-import/skipped-token-source-outcome
             {:id "original-library" :tokens-source? true
              :tokens-source-fallback :tokens-source-deactivated})))
  (t/is (nil? (dashboard-import/skipped-token-source-outcome
               {:id "original-library" :tokens-source? true})))
  (t/is (nil? (dashboard-import/skipped-token-source-outcome
               {:id "ordinary-library"}))))

(t/deftest token-aware-resolution-preserves-co-selected-libraries
  (t/is (= {:command :resolve-import-token-source
            :params {:file-id "imported-file"
                     :library-id "selected-library"
                     :tokens-status-names {}
                     :candidate-ids ["candidate-a" "candidate-b"]
                     :preserve-ids ["candidate-a"]}}
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "original-library" :tokens-source? true
             :tokens-status-names {}
             :candidates [{:id "candidate-a"} {:id "candidate-b"}]}
            "selected-library"
            ["candidate-a" nil])))

  (t/is (= {:command :resolve-import-token-source
            :params {:file-id "imported-file"
                     :library-id "selected-library"
                     :tokens-status-names {}
                     :candidate-ids ["candidate-a" "candidate-b"]}}
           ;; the chosen library and empty selections are never preserved
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "original-library" :tokens-source? true
             :tokens-status-names {}
             :candidates [{:id "candidate-a"} {:id "candidate-b"}]}
            "selected-library"
            ["selected-library" nil])))

  ;; ordinary entries keep the generic command and ignore other selections
  (t/is (= {:command :link-file-to-library
            :params {:file-id "imported-file"
                     :library-id "selected-library"}}
           (dashboard-import/pending-library-resolution-request
            "imported-file"
            {:id "ordinary-library"}
            "selected-library"
            ["candidate-a"]))))
