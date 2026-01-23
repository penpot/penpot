import { useState } from 'react';
import styles from './Example.module.css';

export function Example() {
  const [count, setCount] = useState(0);

  return (
    <div className={styles.container}>
      <h1>Example!</h1>
      <div>
        <h2>Counter: {count}</h2>
        <button onClick={() => setCount(count + 1)}>Increment</button>
        <button onClick={() => setCount(count - 1)}>Decrement</button>
        <button onClick={() => setCount(0)}>Reset</button>
      </div>
    </div>
  );
}

export default Example;