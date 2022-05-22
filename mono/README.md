# Mono

A library for dealing with git repository that have multiple `:local/root` deps. Sort of poor man monorepo toolkit.


```clojure
{build.extra/mono {:git/url "git@github.com:zerg000000/build.extra.git" 
                   :sha "{{replace with commit hash}}" 
                   :deps/root "mono"}}
```

## Getting Started

When you have git repository that have multiple services need separate deployment.
We needs to know which artifact have changes and only deploy the changed artifact(s).


```clojure
(require '[mono.alpha.api :as mono]
         '[clojure.tools.build.api :as b])

(def basis (b/create-basis {}))

(def prev-version (first (mono/last-tags "v1.*" 1)))

(when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                               :deps (mono/deps {:basis basis})}))
  (println "Changes is detected, start deployment")
  (deploy-service))
```

Maybe the paths are not in classpath, custom deps could be added for detecting changes

```clojure
(when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                               :deps (-> (mono/deps {:basis basis})
                                         (conj {:deps/manifest :custom 
                                                :paths ["some extra paths"]}))}))
  (println "Changes is detected, start deployment")
  (deploy-service))
```

Run tests based on changes?

```clojure
(doseq [project (mono/all-deps-edn)]
  (b/set-project-root! (string/replace project #"/deps.edn" ""))
  (when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                                 :deps (mono/deps {:basis (b/create-basis)})}))
    (println "Changes is detected, start test" project)
    (cb/run-tests {:aliases [:test]})))
```

## Assumption

This library make assumptions in order to work properly.

* Git repository
* Using `deps.edn` to manage dependencies
* All sub-projects are under the same repository and referenced by using `:local/root`

## Caveats

* Shallow clone git repository will not have the history for diffing.
* Side effect from files outside of classpath, would be ignored. e.g. loading a json that is outside of classpath.
* It detects file changes by diff git commit hash. However, it use the deps.edn from the current checkout. Some weird situations might occur if you compare commit-a, commit-b, but you checkout commit-c.
* `:local/root` pointing outside of the git repository would be ignore, since it violents the fundamental assumption of monorepo.