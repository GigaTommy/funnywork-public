import { expect, it } from 'vitest';
import { BusinessError } from '../business-feedback';
import { modelOperationError } from '../../views/funnyworker/model-operations-copy';
it('explains catalog limits rather than suggesting a refresh for a version conflict', () => {
  const error = new BusinessError('PRESET_CATALOG_LIMIT', 409);
  expect(modelOperationError(error, false)).toContain('精简指令或修改已有预设');
  expect(modelOperationError(error, true)).toContain('512 items / 4 MiB');
  expect(modelOperationError(error, false)).not.toContain('记录已变化');
});
