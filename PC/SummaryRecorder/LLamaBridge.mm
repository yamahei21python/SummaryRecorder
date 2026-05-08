#import "LLamaBridge.h"
#import <dispatch/dispatch.h>
#import "llama.h"
#include "chat.h"
#include "common.h"

#include <string>
#include <vector>
#include <atomic>

// Stubs for removed llama API functions (referenced by common.cpp/arg.cpp)
const char * llama_commit() { return "unknown"; }
const char * llama_compiler() { return "clang"; }
int llama_build_number() { return 0; }
const char * llama_build_target() { return "arm64"; }
const char * llama_build_info() { return "SummaryRecorder"; }

@implementation LLamaBridgeObjC {
    struct llama_model *_model;
    struct llama_context *_ctx;
    struct llama_sampler *_sampler;
    common_chat_templates_ptr _chatTemplates;
    std::atomic<bool> _isCancelled;
    // Cached from last common_chat_templates_apply
    std::string _thinkingStartTag;
    std::string _thinkingEndTag;
    bool _supportsThinking;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error {
    self = [super init];
    if (self) {
        _model = nullptr;
        _ctx = nullptr;
        _sampler = nullptr;
        _isCancelled = false;
        _supportsThinking = false;

        llama_model_params mparams = llama_model_default_params();
        _model = llama_model_load_from_file([modelPath UTF8String], mparams);
        if (!_model) {
            if (error) {
                *error = [NSError errorWithDomain:@"LLamaBridge" code:1
                                            userInfo:@{NSLocalizedDescriptionKey: @"Failed to load llama model"}];
            }
            return nil;
        }

        // Initialize chat templates from GGUF metadata (Jinja2 engine)
        _chatTemplates = common_chat_templates_init(_model, "");
        if (!_chatTemplates) {
            NSLog(@"[LLamaBridge] WARNING: Failed to init chat templates");
        } else {
            std::string src = common_chat_templates_source(_chatTemplates.get());
            NSLog(@"[LLamaBridge] Chat template loaded, source length: %zu", src.size());
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 2048;
        cparams.n_threads = (int)[[NSProcessInfo processInfo] processorCount];
        cparams.n_threads_batch = cparams.n_threads;

        _ctx = llama_init_from_model(_model, cparams);
        if (!_ctx) {
            llama_model_free(_model);
            _model = nullptr;
            if (error) {
                *error = [NSError errorWithDomain:@"LLamaBridge" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: @"Failed to create context"}];
            }
            return nil;
        }

        // Initialize sampler chain: top_k -> top_p -> temp
        struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        _sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(_sampler, llama_sampler_init_temp(0.6f));
    }
    return self;
}

- (void)dealloc {
    if (_sampler) llama_sampler_free(_sampler);
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
}

#pragma mark - Chat Template (common_chat API)

- (std::string)applyChatTemplateWithSystem:(const std::string &)systemPrompt
                                      text:(const std::string &)text {
    if (!_chatTemplates) {
        // Fallback: raw prompt (no template)
        return systemPrompt + "\n\n" + text;
    }

    // Build messages
    // Gemma 4 does NOT support system role — merge into first user message
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = true;
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;

    std::string userContent;
    if (!systemPrompt.empty()) {
        userContent = systemPrompt + "\n\n" + text;
    } else {
        userContent = text;
    }

    common_chat_msg userMsg;
    userMsg.role = "user";
    userMsg.content = userContent;
    inputs.messages.push_back(userMsg);

    common_chat_params chatParams = common_chat_templates_apply(_chatTemplates.get(), inputs);

    // Cache thinking tags for output parsing
    _thinkingStartTag = chatParams.thinking_start_tag;
    _thinkingEndTag = chatParams.thinking_end_tag;
    _supportsThinking = chatParams.supports_thinking;

    NSLog(@"[LLamaBridge] Template applied: supports_thinking=%d, format=%d, prompt_len=%zu, thinking_start='%s'",
          _supportsThinking, chatParams.format, chatParams.prompt.size(), _thinkingStartTag.c_str());

    return chatParams.prompt;
}

#pragma mark - Think Output Parsing

/// Remove thinking blocks from model output.
/// Gemma 4 Think Mode: <|channel>thought\n...<channel|>final_answer
- (NSString *)stripThinkingContent:(const std::string &)rawOutput {
    std::string result = rawOutput;

    if (_supportsThinking && !_thinkingStartTag.empty() && !_thinkingEndTag.empty()) {
        // Remove all thought blocks: <thinking_start> ... <thinking_end>
        size_t pos = 0;
        while ((pos = result.find(_thinkingStartTag)) != std::string::npos) {
            size_t endPos = result.find(_thinkingEndTag, pos + _thinkingStartTag.size());
            if (endPos != std::string::npos) {
                result.erase(pos, (endPos + _thinkingEndTag.size()) - pos);
            } else {
                // Unclosed thought block — remove from startTag to end
                result.erase(pos);
                break;
            }
        }
    }

    // Also strip any remaining <|channel>...<channel|> blocks
    const std::string channelStart = "<|channel>";
    const std::string channelEnd = "<channel|>";
    size_t pos = 0;
    while ((pos = result.find(channelStart)) != std::string::npos) {
        size_t endPos = result.find(channelEnd, pos + channelStart.size());
        if (endPos != std::string::npos) {
            result.erase(pos, (endPos + channelEnd.size()) - pos);
        } else {
            result.erase(pos);
            break;
        }
    }

    // Strip trailing <turn|>
    const std::string turnEnd = "<turn|>";
    size_t turnPos = result.find(turnEnd);
    if (turnPos != std::string::npos) {
        result.erase(turnPos);
    }

    // Trim leading/trailing whitespace
    size_t start = result.find_first_not_of(" \t\n\r");
    if (start == std::string::npos) {
        result.clear();
    } else {
        size_t end = result.find_last_not_of(" \t\n\r");
        result = result.substr(start, end - start + 1);
    }

    return result.empty() ? nil : @(result.c_str());
}

#pragma mark - Summarize

- (void)summarize:(NSString *)text
     systemPrompt:(NSString *)systemPrompt
       completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    if (!_ctx) {
        completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:3
                                    userInfo:@{NSLocalizedDescriptionKey: @"Model not loaded"}]);
        return;
    }

    _isCancelled = false;

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        __block bool cancelled = false;
        const struct llama_vocab *vocab = llama_model_get_vocab(self->_model);

        // Apply chat template via common_chat API (Think Mode enabled)
        std::string prompt = [self applyChatTemplateWithSystem:std::string([systemPrompt UTF8String])
                                                          text:std::string([text UTF8String])];

        NSLog(@"[LLamaBridge] Prompt (first 300 chars): %.300s", prompt.c_str());

        // Tokenize with special tokens enabled (for <|turn>, <turn|>, <|channel>, etc.)
        std::vector<llama_token> tokens(prompt.size() + 64);
        int32_t n_tokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                          tokens.data(), (int32_t)tokens.size(), true, true);
        if (n_tokens < 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:6
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Tokenization failed"}]);
            });
            return;
        }
        tokens.resize(n_tokens);

        NSLog(@"[LLamaBridge] Tokenized %d tokens", n_tokens);

        // Create batch for prompt tokens and decode
        struct llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        if (llama_decode(self->_ctx, batch) != 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:4
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Prompt decode failed"}]);
            });
            return;
        }

        // Build generation sampler: top_k -> top_p -> temp -> dist
        // NO grammar — GBNF is incompatible with Gemma 4 (Issue #22396)
        struct llama_sampler_chain_params gcparams = llama_sampler_chain_default_params();
        struct llama_sampler *genSampler = llama_sampler_chain_init(gcparams);
        llama_sampler_chain_add(genSampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(genSampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(genSampler, llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(genSampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // Generate tokens
        std::string rawResult;
        const int maxTokens = 1024;
        const llama_token eosToken = llama_vocab_eos(vocab);

        for (int i = 0; i < maxTokens; i++) {
            if (self->_isCancelled.load()) {
                cancelled = true;
                break;
            }

            llama_token newToken = llama_sampler_sample(genSampler, self->_ctx, -1);

            if (newToken == eosToken) break;

            // Accept token into sampler
            llama_sampler_accept(genSampler, newToken);

            // Convert token to text (special=true for chat tokens)
            char buf[256];
            int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
            if (n > 0) {
                rawResult.append(buf, n);
            }

            // Create batch for single token and decode
            struct llama_batch decodeBatch = llama_batch_get_one(&newToken, 1);
            if (llama_decode(self->_ctx, decodeBatch) != 0) break;
        }

        llama_sampler_free(genSampler);

        // Strip thinking content from output
        NSString *cleanResult = [self stripThinkingContent:rawResult];

        NSLog(@"[LLamaBridge] Raw output (first 300 chars): %.300s", rawResult.c_str());
        NSLog(@"[LLamaBridge] Clean output: %@", cleanResult);

        dispatch_async(dispatch_get_main_queue(), ^{
            if (cancelled) {
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:5
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Cancelled"}]);
            } else {
                completion(cleanResult, nil);
            }
        });
    });
}

- (void)cancel {
    _isCancelled = true;
}

@end
