(ns frontend-tests.tokens.token-status-test
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.token-status :as ctos]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [clojure.test :as t]))

(defn- setup-file-with-tokens
  "Create a file with several token themes and token sets, some active and
  some inactive. Returns the file map with :tokens-lib and :token-status
  in its :data."
  []
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib
       #(-> %
            ;; Add token sets
            (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-active)
                                               :name "set-active"))
            (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-inactive)
                                               :name "set-inactive"))
            ;; Add themes
            (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-active)
                                                   :name "theme-active"
                                                   :group "group-1"
                                                   :sets #{"set-active"}))
            (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-inactive)
                                                   :name "theme-inactive"
                                                   :group "group-1"
                                                   :sets #{"set-inactive"}))))
      (tht/update-token-status
       #(-> %
            (ctos/activate-theme (thi/id :theme-active))
            (ctos/activate-set (thi/id :set-active))))))

(t/deftest test-token-status-active-inactive
  (t/testing "lookup helpers and active checks"
    (let [file          (setup-file-with-tokens)
          state         {:current-file-id (:id file)
                         :current-page-id (thf/current-page-id file)
                         :files {(:id file) file}}
          token-status  (dsh/lookup-token-status state)]

      ;; Theme lookup via token-status
      (t/is (ctos/theme-active? token-status (thi/id :theme-active))
            "active theme should be active via status")
      (t/is (not (ctos/theme-active? token-status (thi/id :theme-inactive)))
            "inactive theme should be inactive via status")

      ;; Set lookup via token-status
      (t/is (ctos/set-active? token-status (thi/id :set-active))
            "active set should be active via status")
      (t/is (not (ctos/set-active? token-status (thi/id :set-inactive)))
            "inactive set should be inactive via status"))))

