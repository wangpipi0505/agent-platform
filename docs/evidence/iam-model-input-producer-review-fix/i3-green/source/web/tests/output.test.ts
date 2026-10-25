import {it,expect} from 'vitest';import {outputText, outputWarning, finalAnswer} from '../shared/content';
it('business conversation displays answer text without model routing metadata and preserves structured business results',()=>{expect(outputText({text:'回答',actualModel:{endpoint:'internal'},usage:{totalTokens:12}})).toBe('回答');expect(JSON.parse(outputText({value:0}))).toEqual({value:0});});

it('shows failed validation as a warning without pretending an accepted structured result',()=>{
  const result={text:'not JSON',outputValidation:{valid:false,onInvalid:'PASS_WITH_FLAG',reason:'$.count must be integer',path:'$.count'},actualModel:{endpoint:'private'}};
  expect(outputWarning(result)).toContain('未通过格式校验');
  expect(outputWarning(result)).toContain('$.count must be integer');
  expect(outputWarning({outputValidation:{valid:true}})).toBe('');
  expect(outputWarning({text:'ordinary answer'})).toBe('');
  expect(finalAnswer(result)).toEqual({text:'not JSON',warning:outputWarning(result)});
});

it('uses the durable final answer to replace streamed invalid attempts, including empty accepted text',()=>{
  expect(finalAnswer({text:'corrected final',outputValidation:{valid:true}})).toEqual({text:'corrected final',warning:''});
  expect(finalAnswer({text:'',structured:null,outputValidation:{valid:true}})).toEqual({text:'',warning:''});
});
