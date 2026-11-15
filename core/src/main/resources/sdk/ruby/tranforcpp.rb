# frozen_string_literal: true

# Protocol-v1 SDK for Ruby. stdout belongs to the protocol; log diagnostics with warn.
#
# Programs can call each other: Tranfor.handle exposes a method, Tranfor.call waits for its
# string result, Tranfor.publish / Tranfor.subscribe broadcast messages. Call Tranfor.call from
# handlers or the main thread; the SDK reads stdin on that thread.
require 'json'

module Tranfor
  class CallError < StandardError; end

  @output = Mutex.new
  @handlers = Hash.new { |hash, key| hash[key] = [] }
  @topics = Hash.new { |hash, key| hash[key] = [] }
  @methods = {}
  @backlog = []
  @replies = {}
  @next_id = 0
  $stdout.sync = true

  # This program's ID as the server knows it (directory name or "@root").
  PROGRAM_ID = ENV.fetch('TRANFORCPP_PROGRAM_ID', '')

  module_function

  def send_action(action, fields = {})
    line = JSON.generate({ action: action }.merge(fields))
    @output.synchronize { $stdout.write(line, "\n") }
  end

  def broadcast(message) = send_action('broadcast', message: message.to_s)
  def send_message(player, message) = send_action('sendMessage', player: player.to_s, message: message.to_s)
  def console(message) = send_action('console', message: message.to_s)
  def dispatch_command(command) = send_action('executeCommand', command: command.to_s)

  # Tranfor.on('PlayerJoin') { |player| ... }
  def on(event, &block) = @handlers[event] << block
  # Expose a method; the block's value is returned to the caller as a string.
  def handle(method, &block) = @methods[method] = block
  # Receive Tranfor.publish(topic, ...) from other programs: block.call(sender, *args).
  def subscribe(topic, &block) = @topics[topic] << block

  def call(target, method, *args)
    id = (@next_id += 1).to_s
    send_action('call', target: target.to_s, method: method.to_s, args: args.map(&:to_s), id: id)
    until @replies.key?(id)
      message = read_message
      raise CallError, "input closed while waiting for #{target}" if message.nil?

      case message['event']
      when 'reply' then @replies[message['id'].to_s] = message
      when 'call' then serve(message) # answer nested calls (A -> B -> A) instead of deadlocking
      else
        @backlog << message
        raise CallError, 'server is shutting down' if message['event'] == 'shutdown'
      end
    end
    reply = @replies.delete(id)
    raise CallError, reply.fetch('error', 'call failed') unless reply['ok']

    reply.fetch('result', '')
  end

  def notify(target, method, *args)
    send_action('call', target: target.to_s, method: method.to_s, args: args.map(&:to_s))
  end

  def publish(topic, *args) = send_action('publish', topic: topic.to_s, args: args.map(&:to_s))

  def read_message
    while (line = $stdin.gets)
      return JSON.parse(line) unless line.strip.empty?
    end
    nil
  end

  def serve(message)
    handler = @methods[message['method']]
    begin
      raise CallError, "unknown method #{message['method']}" unless handler

      result = handler.call(*message.fetch('args', []))
      send_action('reply', id: message['id'], result: result.to_s) if message.key?('id')
    rescue StandardError => e
      if message.key?('id')
        send_action('reply', id: message['id'], error: "#{e.class}: #{e.message}")
      else
        warn "tranforcpp: notification failed: #{e.class}: #{e.message}"
      end
    end
  end

  def run
    loop do
      event = @backlog.shift || read_message
      break if event.nil?

      kind = event['event']
      next serve(event) if kind == 'call'
      next if kind == 'reply' # a late reply after a timeout

      @topics[event['topic']].each { |h| h.call(event.fetch('from', ''), *event['args']) } if kind == 'message'
      @handlers[kind].each { |h| h.call(*event['args']) }
      break if kind == 'shutdown'
    end
  end

  singleton_class.send(:alias_method, :sendMessage, :send_message)
  singleton_class.send(:alias_method, :dispatchCommand, :dispatch_command)
end
