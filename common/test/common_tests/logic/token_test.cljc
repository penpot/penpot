(ns common-tests.logic.token-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.tokens :as clt]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file [lib-fn]
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib lib-fn)))

(t/deftest generate-toggle-token-set-test
  (t/testing "toggling an active set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set (pcb/empty-changes) (tht/get-tokens-lib file) "foo/bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an inactive set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set (pcb/empty-changes) (tht/get-tokens-lib file) "foo/bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an set with hidden theme already active will toggle set in hidden theme"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-hidden-token-theme))
                                (ctob/set-active-themes #{ctob/hidden-token-theme-path})))

          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) "G-foo/S-bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= (ctob/get-active-theme-paths redo-lib) (ctob/get-active-theme-paths undo-lib)))

      (t/is (= #{"foo/bar"} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (some? (ctob/get-hidden-theme undo-lib))))))

(t/deftest generate-toggle-token-set-group-test
  (t/testing "toggling set group with no active sets inside will activate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) "G-foo/G-bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{"foo/bar/baz" "foo/bar/baz/baz-child"} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling set group with partially active sets inside will deactivate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar/baz"}))
                                (ctob/set-active-themes #{"/theme"})))

          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) "G-foo/G-bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib))))))
