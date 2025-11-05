import styles from './test-tsx.module.css';

import { translate } from '../penpot-bridge';

export const TestTsxComponent = () => {
  return (
    <div>
        <h2 className={styles.title}>{translate("labels.delete")} xx</h2>
    </div>
  );
};