// Generates the problem set. Expected outputs are computed by the reference solutions below, so they cannot drift.
//   node tools/gen-problems.mjs
// Writes backend/src/main/resources/problems.json (served to users) and backend/src/test/resources/references.json
// (reference solutions, used only by the tests to check that the sandbox accepts a correct solution for every problem).
import fs from "node:fs";

let seed = 20261009;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
const ri = (a, b) => a + Math.floor(rnd() * (b - a + 1));
const arr = (n, a, b) => Array.from({ length: n }, () => ri(a, b));

const P = [];
const add = (p) => P.push(p);

add({
  slug: "two-sum", title: "Two Sum", difficulty: "easy", topics: ["arrays", "hash-table"], functionName: "twoSum", argNames: ["nums", "target"],
  statement: `Given an array of integers \`nums\` and an integer \`target\`, return the indices of the two numbers that add up to \`target\`, as an array \`[i, j]\` with \`i < j\`.

Each input has exactly one solution, and you may not use the same element twice.

**Example**

\`\`\`
nums = [2, 7, 11, 15], target = 9  ->  [0, 1]
\`\`\`

**Constraints:** 2 <= nums.length <= 10000, values fit in 32 bits.`,
  reference: `function twoSum(nums, target) { const seen = new Map(); for (let j = 0; j < nums.length; j++) { const i = seen.get(target - nums[j]); if (i !== undefined) return [i, j]; seen.set(nums[j], j); } }`,
  tests: () => {
    const t = [[[2, 7, 11, 15], 9], [[3, 2, 4], 6], [[3, 3], 6], [[-1, -2, -3, -4, -5], -8], [[0, 4, 3, 0], 0]];
    for (let k = 0; k < 4; k++) { const a = Array.from(new Set(arr(3000, -100000, 100000))); const i = ri(0, a.length - 2), j = ri(i + 1, a.length - 1); t.push([a, a[i] + a[j]]); }
    return t;
  },
  sample: 2,
});

add({
  slug: "fizz-buzz", title: "Fizz Buzz", difficulty: "easy", topics: ["math", "strings"], functionName: "fizzBuzz", argNames: ["n"],
  statement: `Return an array of strings for the numbers from 1 to \`n\`:

- \`"FizzBuzz"\` if the number is divisible by 3 and 5,
- \`"Fizz"\` if divisible by 3,
- \`"Buzz"\` if divisible by 5,
- otherwise the number as a string.

**Example**

\`\`\`
n = 5  ->  ["1", "2", "Fizz", "4", "Buzz"]
\`\`\``,
  reference: `function fizzBuzz(n) { const r = []; for (let i = 1; i <= n; i++) r.push(i % 15 === 0 ? "FizzBuzz" : i % 3 === 0 ? "Fizz" : i % 5 === 0 ? "Buzz" : String(i)); return r; }`,
  tests: () => [[1], [3], [5], [15], [16], [100], [1000]].map((x) => x),
  sample: 2,
});

add({
  slug: "valid-parentheses", title: "Valid Parentheses", difficulty: "easy", topics: ["stack", "strings"], functionName: "isValid", argNames: ["s"],
  statement: `Given a string \`s\` made only of \`()[]{}\`, return \`true\` if the brackets are balanced: every opening bracket is closed by the same type of bracket, in the correct order.

**Examples**

\`\`\`
"()[]{}"  ->  true
"(]"      ->  false
"([)]"    ->  false
\`\`\``,
  reference: `function isValid(s) { const st = [], m = { ")": "(", "]": "[", "}": "{" }; for (const c of s) { if (c in m) { if (st.pop() !== m[c]) return false; } else st.push(c); } return st.length === 0; }`,
  tests: () => {
    const t = [["()"], ["()[]{}"], ["(]"], ["([)]"], ["{[]}"], [""], ["("], [")"], ["(((((((((())))))))))"], ["((((((((((]"]];
    const chars = "()[]{}";
    for (let k = 0; k < 6; k++) t.push([Array.from({ length: ri(4, 30) }, () => chars[ri(0, 5)]).join("")]);
    t.push(["(".repeat(2500) + ")".repeat(2500)]);
    return t;
  },
  sample: 3,
});

add({
  slug: "maximum-subarray", title: "Maximum Subarray", difficulty: "medium", topics: ["arrays", "dynamic-programming"], functionName: "maxSubArray", argNames: ["nums"],
  statement: `Find the contiguous, non-empty subarray of \`nums\` with the largest sum and return that sum.

**Example**

\`\`\`
[-2, 1, -3, 4, -1, 2, 1, -5, 4]  ->  6     (the subarray [4, -1, 2, 1])
\`\`\`

**Constraints:** 1 <= nums.length <= 100000. An O(n) solution is expected.`,
  reference: `function maxSubArray(nums) { let best = nums[0], cur = 0; for (const x of nums) { cur = Math.max(x, cur + x); best = Math.max(best, cur); } return best; }`,
  tests: () => {
    const t = [[[-2, 1, -3, 4, -1, 2, 1, -5, 4]], [[1]], [[5, 4, -1, 7, 8]], [[-3, -1, -2]], [[0, 0, 0]], [[-1]]];
    for (let k = 0; k < 4; k++) t.push([arr(20000, -50, 50)]);
    t.push([arr(60000, -1000, 1000)]);
    return t.map((x) => x);
  },
  sample: 2,
});

add({
  slug: "longest-substring-without-repeating", title: "Longest Substring Without Repeating Characters", difficulty: "medium", topics: ["strings", "sliding-window", "hash-table"], functionName: "lengthOfLongestSubstring", argNames: ["s"],
  statement: `Return the length of the longest substring of \`s\` that contains no repeated character.

**Examples**

\`\`\`
"abcabcbb"  ->  3   ("abc")
"bbbbb"     ->  1
"pwwkew"    ->  3   ("wke")
\`\`\`

**Constraints:** 0 <= s.length <= 50000.`,
  reference: `function lengthOfLongestSubstring(s) { const last = new Map(); let start = 0, best = 0; for (let i = 0; i < s.length; i++) { const p = last.get(s[i]); if (p !== undefined && p >= start) start = p + 1; last.set(s[i], i); best = Math.max(best, i - start + 1); } return best; }`,
  tests: () => {
    const t = [["abcabcbb"], ["bbbbb"], ["pwwkew"], [""], ["a"], ["dvdf"], ["abba"], [" "]];
    const abc = "abcdefghijklmnopqrstuvwxyz";
    for (let k = 0; k < 3; k++) t.push([Array.from({ length: 30000 }, () => abc[ri(0, 25)]).join("")]);
    t.push([abc.repeat(1500)]);
    return t;
  },
  sample: 3,
});

add({
  slug: "merge-intervals", title: "Merge Intervals", difficulty: "medium", topics: ["arrays", "sorting"], functionName: "merge", argNames: ["intervals"],
  statement: `Given an array of intervals \`[start, end]\`, merge all overlapping intervals (intervals that touch, like \`[1,4]\` and \`[4,5]\`, overlap) and return the result sorted by start.

**Example**

\`\`\`
[[1,3],[2,6],[8,10],[15,18]]  ->  [[1,6],[8,10],[15,18]]
\`\`\``,
  reference: `function merge(iv) { const s = iv.map((x) => [x[0], x[1]]).sort((a, b) => a[0] - b[0] || a[1] - b[1]); const out = []; for (const x of s) { const l = out[out.length - 1]; if (l && x[0] <= l[1]) l[1] = Math.max(l[1], x[1]); else out.push(x); } return out; }`,
  tests: () => {
    const t = [[[[1, 3], [2, 6], [8, 10], [15, 18]]], [[[1, 4], [4, 5]]], [[[1, 4], [0, 4]]], [[[1, 4], [2, 3]]], [[[5, 6]]], [[[2, 3], [4, 5], [6, 7], [8, 9], [1, 10]]]];
    for (let k = 0; k < 4; k++) t.push([Array.from({ length: 4000 }, () => { const a = ri(0, 100000); return [a, a + ri(0, 40)]; })]);
    return t;
  },
  sample: 2,
});

add({
  slug: "coin-change", title: "Coin Change", difficulty: "medium", topics: ["dynamic-programming"], functionName: "coinChange", argNames: ["coins", "amount"],
  statement: `Given coin denominations \`coins\` (unlimited supply of each) and a target \`amount\`, return the **fewest** coins that sum to \`amount\`, or \`-1\` if it is impossible.

**Examples**

\`\`\`
coins = [1, 2, 5], amount = 11  ->  3    (5 + 5 + 1)
coins = [2], amount = 3         ->  -1
\`\`\`

**Constraints:** 1 <= coins.length <= 12, 0 <= amount <= 10000. Plain recursion will be too slow.`,
  reference: `function coinChange(coins, amount) { const dp = new Array(amount + 1).fill(Infinity); dp[0] = 0; for (let a = 1; a <= amount; a++) for (const c of coins) if (c <= a && dp[a - c] + 1 < dp[a]) dp[a] = dp[a - c] + 1; return dp[amount] === Infinity ? -1 : dp[amount]; }`,
  tests: () => [[[1, 2, 5], 11], [[2], 3], [[1], 0], [[1], 2], [[186, 419, 83, 408], 6249], [[2, 5, 10, 1], 27], [[5, 7, 9], 4999], [[3, 7, 405, 436], 8839], [[1, 3, 4], 6], [[2], 10000]],
  sample: 2,
});

add({
  slug: "trapping-rain-water", title: "Trapping Rain Water", difficulty: "hard", topics: ["arrays", "two-pointers"], functionName: "trap", argNames: ["height"],
  statement: `Given \`height\`, an array of non-negative integers where each value is the height of a bar of width 1, compute how much rain water is trapped between the bars.

**Example**

\`\`\`
[0,1,0,2,1,0,1,3,2,1,2,1]  ->  6
\`\`\`

**Constraints:** 0 <= height.length <= 100000. Aim for O(n) time.`,
  reference: `function trap(h) { let l = 0, r = h.length - 1, lm = 0, rm = 0, w = 0; while (l < r) { if (h[l] < h[r]) { lm = Math.max(lm, h[l]); w += lm - h[l++]; } else { rm = Math.max(rm, h[r]); w += rm - h[r--]; } } return w; }`,
  tests: () => {
    const t = [[[0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1]], [[4, 2, 0, 3, 2, 5]], [[]], [[3]], [[1, 2, 3, 4]], [[5, 4, 3, 2, 1]], [[2, 0, 2]]];
    for (let k = 0; k < 3; k++) t.push([arr(30000, 0, 500)]);
    t.push([arr(80000, 0, 100000)]);
    return t;
  },
  sample: 2,
});

add({
  slug: "edit-distance", title: "Edit Distance", difficulty: "hard", topics: ["strings", "dynamic-programming"], functionName: "minDistance", argNames: ["word1", "word2"],
  statement: `Return the minimum number of single-character operations (insert, delete or replace) needed to turn \`word1\` into \`word2\`.

**Examples**

\`\`\`
"horse", "ros"          ->  3
"intention", "execution" ->  5
\`\`\`

**Constraints:** 0 <= length <= 500 for each word.`,
  reference: `function minDistance(a, b) { let prev = Array.from({ length: b.length + 1 }, (_, j) => j); for (let i = 1; i <= a.length; i++) { const cur = [i]; for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); prev = cur; } return prev[b.length]; }`,
  tests: () => {
    const t = [["horse", "ros"], ["intention", "execution"], ["", ""], ["a", ""], ["", "abc"], ["kitten", "sitting"], ["same", "same"]];
    const s = (n) => Array.from({ length: n }, () => "abcd"[ri(0, 3)]).join("");
    for (let k = 0; k < 3; k++) t.push([s(300), s(300)]);
    t.push([s(480), s(500)]);
    return t;
  },
  sample: 2,
});

// ---- evaluate the reference solutions to produce expected outputs ----
const problems = [];
const references = {};
for (const p of P) {
  const fn = new Function(`${p.reference}; return ${p.functionName};`)();
  const raw = p.tests().map((a) => (Array.isArray(a) && p.argNames.length === 1 && !(a.length === 1 && p.argNames.length === 1 && false) ? a : a));
  const tests = raw.map((args, i) => {
    const a = args.length === p.argNames.length ? args : [args];
    const expected = fn(...JSON.parse(JSON.stringify(a)));
    return { args: a, expected: expected === undefined ? null : expected, hidden: i >= p.sample };
  });
  problems.push({ slug: p.slug, title: p.title, difficulty: p.difficulty, topics: p.topics, functionName: p.functionName, argNames: p.argNames, statement: p.statement, tests });
  references[p.slug] = p.reference;
}
fs.writeFileSync("backend/src/main/resources/problems.json", JSON.stringify(problems));
fs.writeFileSync("backend/src/test/resources/references.json", JSON.stringify(references, null, 1));
console.log(`${problems.length} problems, ${problems.reduce((n, p) => n + p.tests.length, 0)} tests`);
